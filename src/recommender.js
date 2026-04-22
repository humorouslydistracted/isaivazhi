/**
 * Session-aware music recommendation engine (pure JavaScript).
 *
 * Features:
 * - Single-song recommendations
 * - Session blend (weighted average of recent listens)
 * - Blended recommendation (current song + session history)
 * - Surprise me (maximally different from session)
 * - Mood clusters via k-means on CLAP embeddings
 */

// --- Vector math utilities ---

function dotProduct(a, b) {
  let sum = 0;
  for (let i = 0; i < a.length; i++) sum += a[i] * b[i];
  return sum;
}

function vecAdd(a, b) {
  const out = new Float32Array(a.length);
  for (let i = 0; i < a.length; i++) out[i] = a[i] + b[i];
  return out;
}

function vecScale(v, s) {
  const out = new Float32Array(v.length);
  for (let i = 0; i < v.length; i++) out[i] = v[i] * s;
  return out;
}

function vecNorm(v) {
  let sum = 0;
  for (let i = 0; i < v.length; i++) sum += v[i] * v[i];
  return Math.sqrt(sum);
}

function vecNormalize(v) {
  const n = vecNorm(v);
  if (n > 0) return vecScale(v, 1 / n);
  return v;
}

function weightedAverage(vectors, weights) {
  const dim = vectors[0].length;
  const out = new Float32Array(dim);
  for (let j = 0; j < vectors.length; j++) {
    const w = weights[j];
    const vec = vectors[j];
    for (let i = 0; i < dim; i++) out[i] += w * vec[i];
  }
  return out;
}

// --- Seeded RNG (matches numpy RandomState(42) style) ---

class SeededRNG {
  constructor(seed) {
    this.state = seed;
  }
  // xorshift32
  next() {
    let x = this.state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    this.state = x;
    return (x >>> 0) / 4294967296;
  }
  choice(arr) {
    return arr[Math.floor(this.next() * arr.length)];
  }
  sample(n, count) {
    // Sample `count` unique indices from [0, n)
    const indices = [];
    const used = new Set();
    while (indices.length < count) {
      const idx = Math.floor(this.next() * n);
      if (!used.has(idx)) {
        used.add(idx);
        indices.push(idx);
      }
    }
    return indices;
  }
  shuffle(arr) {
    // Fisher-Yates
    for (let i = arr.length - 1; i > 0; i--) {
      const j = Math.floor(this.next() * (i + 1));
      [arr[i], arr[j]] = [arr[j], arr[i]];
    }
    return arr;
  }
}

// --- Recommender class ---

class Recommender {
  /**
   * @param {Float32Array[]} embeddings - Array of L2-normalized embedding vectors
   * @param {Object} opts
   */
  constructor(embeddings, opts = {}) {
    this.embeddings = embeddings;
    // Candidate pool must be much larger than TOP_N so that after history
    // exclusion + ghost-song filtering the final queue still contains ~50 items.
    this.nCandidates = Math.min(opts.nCandidates || 250, embeddings.length);
    this.lam = opts.lam ?? 0.8;
    this.decay = opts.decay ?? 0.75;
    this.sessionWindow = opts.sessionWindow ?? 12;
    this.currentWeight = opts.currentWeight ?? 0.6;
    this.dim = embeddings.length > 0 ? embeddings[0].length : 0;
  }

  /**
   * Find n nearest neighbors via dot product (= cosine for normalized vecs).
   * Uses partial sort for efficiency.
   */
  _findNearest(queryVec, n) {
    n = Math.min(n, this.embeddings.length);
    const len = this.embeddings.length;
    const sims = new Float32Array(len);
    for (let i = 0; i < len; i++) {
      const vec = this.embeddings[i];
      sims[i] = vec ? dotProduct(vec, queryVec) : -Infinity;
    }

    // Min-heap of size n for O(n·k) top-k selection instead of O(n·log·n) full sort
    const heap = []; // array of {idx, sim}, min-heap by sim
    for (let i = 0; i < len; i++) {
      if (heap.length < n) {
        heap.push({ idx: i, sim: sims[i] });
        // Bubble up to maintain min-heap
        let c = heap.length - 1;
        while (c > 0) {
          const p = (c - 1) >> 1;
          if (heap[c].sim < heap[p].sim) { [heap[c], heap[p]] = [heap[p], heap[c]]; c = p; }
          else break;
        }
      } else if (sims[i] > heap[0].sim) {
        // Replace min element and sift down
        heap[0] = { idx: i, sim: sims[i] };
        let p = 0;
        while (true) {
          let smallest = p;
          const l = 2 * p + 1, r = 2 * p + 2;
          if (l < n && heap[l].sim < heap[smallest].sim) smallest = l;
          if (r < n && heap[r].sim < heap[smallest].sim) smallest = r;
          if (smallest !== p) { [heap[p], heap[smallest]] = [heap[smallest], heap[p]]; p = smallest; }
          else break;
        }
      }
    }

    // Extract top-n sorted by similarity descending
    const topIndices = heap.sort((a, b) => b.sim - a.sim).map(h => h.idx);
    return { sims, topIndices };
  }

  /**
   * Blend recently listened songs into a session vector.
   * Songs listened > 30% qualify (using fraction² for smooth weighting).
   * More recent songs weighted higher via exponential decay.
   * Skipped songs (< 30%) are subtracted as negative signal.
   * @param {Array<{id: number, listen_fraction: number}>} listened - positive listens
   * @param {Array<{id: number, listen_fraction: number}>} skipped - skipped songs (optional)
   * @returns {Float32Array|null}
   */
  buildSessionVector(listened, skipped = []) {
    let qualified = listened.filter(s => s.listen_fraction > 0.3);
    if (qualified.length === 0) {
      qualified = listened.slice(-this.sessionWindow);
    }
    const recent = qualified.slice(-this.sessionWindow);

    if (recent.length === 0) return null;
    if (recent.length === 1 && skipped.length === 0) {
      return new Float32Array(this.embeddings[recent[0].id]);
    }

    // Positive signal: use fraction² for smooth weighting
    const weights = [];
    for (let i = 0; i < recent.length; i++) {
      const age = recent.length - 1 - i;
      const fracWeight = recent[i].listen_fraction * recent[i].listen_fraction;
      weights.push(Math.pow(this.decay, age) * fracWeight);
    }
    const wSum = weights.reduce((a, b) => a + b, 0);
    for (let i = 0; i < weights.length; i++) weights[i] /= wSum;

    const vecs = recent.map(s => this.embeddings[s.id]);
    let sessionVec = weightedAverage(vecs, weights);

    // Negative signal: subtract skipped songs (gentle push away)
    if (skipped.length > 0) {
      const skipWeight = 0.15;
      const recentSkips = skipped.slice(-5);
      for (const s of recentSkips) {
        const skipVec = this.embeddings[s.id];
        const penalty = skipWeight * (1 - s.listen_fraction);
        sessionVec = vecAdd(sessionVec, vecScale(skipVec, -penalty / recentSkips.length));
      }
    }

    return vecNormalize(sessionVec);
  }

  /**
   * Get MMR-diversified recommendations from a query vector.
   * @param {Float32Array} queryVec
   * @param {number} topN
   * @param {Set<number>} exclude
   * @param {Map<number, string>|null} artistMap - embIdx -> artist name (for artist bonus)
   * @param {string|null} currentArtist - boost songs by this artist
   * @returns {Array<{id: number, similarity: number}>}
   */
  recommend(queryVec, topN = 10, exclude = null, artistMap = null, currentArtist = null) {
    exclude = exclude || new Set();
    const { sims, topIndices } = this._findNearest(queryVec, this.nCandidates);
    let candidates = topIndices.filter(i => !exclude.has(i));

    const artistBonus = 0.05;
    const selected = [];
    while (selected.length < topN && candidates.length > 0) {
      let bestScore = -Infinity;
      let bestIdx = null;
      let bestPos = -1;

      for (let ci = 0; ci < candidates.length; ci++) {
        const c = candidates[ci];
        const simQ = dotProduct(this.embeddings[c], queryVec);

        let simS = 0;
        if (selected.length > 0) {
          for (const s of selected) {
            const sim = dotProduct(this.embeddings[c], this.embeddings[s]);
            if (sim > simS) simS = sim;
          }
        }

        let score = this.lam * simQ - (1 - this.lam) * simS;

        // Artist bonus: small boost for songs by the current artist
        if (artistMap && currentArtist && artistMap.get(c) === currentArtist) {
          score += artistBonus;
        }

        if (score > bestScore) {
          bestScore = score;
          bestIdx = c;
          bestPos = ci;
        }
      }

      if (bestIdx === null) break;
      selected.push(bestIdx);
      candidates.splice(bestPos, 1);
    }

    return selected.map(sid => ({
      id: sid,
      similarity: Math.round(dotProduct(this.embeddings[sid], queryVec) * 1000) / 1000,
    }));
  }

  /**
   * Recommend based on a single song.
   */
  recommendSingle(songId, topN = 10, exclude = null) {
    return this.recommend(this.embeddings[songId], topN, exclude);
  }

  /**
   * Blend current song with session history.
   * currentWeight controls the mix (0.6 = 60% current, 40% history).
   */
  recommendBlended(songId, sessionVec, topN = 10, exclude = null) {
    const currentVec = this.embeddings[songId];
    let blended;
    if (sessionVec != null) {
      blended = vecAdd(
        vecScale(currentVec, this.currentWeight),
        vecScale(sessionVec, 1 - this.currentWeight)
      );
      blended = vecNormalize(blended);
    } else {
      blended = currentVec;
    }
    return this.recommend(blended, topN, exclude);
  }

  /**
   * Find the song most different from the current session.
   * @param {Float32Array|null} sessionVec
   * @param {Set<number>} exclude
   * @returns {number|null} song_id
   */
  surprise(sessionVec, exclude = null) {
    exclude = exclude || new Set();
    if (sessionVec == null) {
      // No session — pick random
      const candidates = [];
      for (let i = 0; i < this.embeddings.length; i++) {
        if (!exclude.has(i)) candidates.push(i);
      }
      if (candidates.length === 0) return null;
      return candidates[Math.floor(Math.random() * candidates.length)];
    }

    let minSim = Infinity;
    let minIdx = null;
    for (let i = 0; i < this.embeddings.length; i++) {
      if (exclude.has(i)) continue;
      const sim = dotProduct(this.embeddings[i], sessionVec);
      if (sim < minSim) {
        minSim = sim;
        minIdx = i;
      }
    }
    return minIdx;
  }

  /**
   * K-means clustering on normalized embeddings using cosine similarity.
   * @param {number} nClusters
   * @param {number} maxIter
   * @returns {Int32Array} cluster labels (one per song)
   */
  computeClusters(nClusters = 15, maxIter = 50) {
    const n = this.embeddings.length;
    if (n < nClusters) return new Int32Array(n); // all zeros

    // Init: pick nClusters random songs as centroids (seeded)
    const rng = new SeededRNG(42);
    const initIndices = rng.sample(n, nClusters);
    const centroids = initIndices.map(i => new Float32Array(this.embeddings[i]));

    let labels = new Int32Array(n);

    for (let iter = 0; iter < maxIter; iter++) {
      // Assign each song to nearest centroid
      const newLabels = new Int32Array(n);
      for (let i = 0; i < n; i++) {
        let bestSim = -Infinity;
        let bestJ = 0;
        for (let j = 0; j < nClusters; j++) {
          const sim = dotProduct(this.embeddings[i], centroids[j]);
          if (sim > bestSim) {
            bestSim = sim;
            bestJ = j;
          }
        }
        newLabels[i] = bestJ;
      }

      // Check convergence
      let converged = true;
      for (let i = 0; i < n; i++) {
        if (labels[i] !== newLabels[i]) { converged = false; break; }
      }
      labels = newLabels;
      if (converged) break;

      // Update centroids
      for (let j = 0; j < nClusters; j++) {
        const centroid = new Float32Array(this.dim);
        let count = 0;
        for (let i = 0; i < n; i++) {
          if (labels[i] === j) {
            for (let d = 0; d < this.dim; d++) centroid[d] += this.embeddings[i][d];
            count++;
          }
        }
        if (count > 0) {
          for (let d = 0; d < this.dim; d++) centroid[d] /= count;
          const norm = vecNorm(centroid);
          if (norm > 0) {
            for (let d = 0; d < this.dim; d++) centroid[d] /= norm;
          }
          centroids[j] = centroid;
        }
      }
    }

    return labels;
  }
}

// Export for use in other modules
export {
  Recommender,
  dotProduct,
  vecAdd,
  vecScale,
  vecNorm,
  vecNormalize,
  weightedAverage,
  SeededRNG,
};
