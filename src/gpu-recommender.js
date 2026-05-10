/**
 * WebGPU-accelerated vector operations for the recommender.
 *
 * Uses compute shaders to parallelize the two hottest paths:
 *   1. Dot product of a query vector against all stored embeddings.
 *      (used by nearest-neighbor search)
 *   2. K-means assignment step — for each song, find the closest centroid
 *      across all clusters in one dispatch.
 *
 * The CPU recommender remains the source of truth. This module is loaded
 * opportunistically: if WebGPU is unavailable (older WebView, denied adapter,
 * device lost), every method falls back gracefully to null and the caller
 * uses the existing CPU code path.
 *
 * Vector layout for vectors and centroids buffers is row-major flat:
 *   vectors[i * dim + k]   = song i, dim k
 *   centroids[j * dim + k] = cluster j, dim k
 *
 * Workgroup size is 64 — a portable default that works across Adreno, Mali,
 * PowerVR, and desktop GPUs without device-specific tuning.
 */

const WGSL_DOT_KERNEL = /* wgsl */ `
struct Dims {
  count: u32,
  dim: u32,
};

@group(0) @binding(0) var<storage, read> vectors: array<f32>;
@group(0) @binding(1) var<storage, read> query: array<f32>;
@group(0) @binding(2) var<storage, read_write> output: array<f32>;
@group(0) @binding(3) var<uniform> dims: Dims;

@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i >= dims.count) { return; }
  var sum: f32 = 0.0;
  let offset = i * dims.dim;
  for (var k: u32 = 0u; k < dims.dim; k = k + 1u) {
    sum = sum + vectors[offset + k] * query[k];
  }
  output[i] = sum;
}
`;

const WGSL_KMEANS_ASSIGN_KERNEL = /* wgsl */ `
struct KDims {
  count: u32,
  dim: u32,
  numClusters: u32,
  _pad: u32,
};

@group(0) @binding(0) var<storage, read> vectors: array<f32>;
@group(0) @binding(1) var<storage, read> centroids: array<f32>;
@group(0) @binding(2) var<storage, read_write> labels: array<i32>;
@group(0) @binding(3) var<uniform> dims: KDims;

@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i >= dims.count) { return; }
  var bestSim: f32 = -3.4e30;
  var bestJ: i32 = 0;
  let songOffset = i * dims.dim;
  for (var j: u32 = 0u; j < dims.numClusters; j = j + 1u) {
    let centroidOffset = j * dims.dim;
    var sim: f32 = 0.0;
    for (var k: u32 = 0u; k < dims.dim; k = k + 1u) {
      sim = sim + vectors[songOffset + k] * centroids[centroidOffset + k];
    }
    if (sim > bestSim) {
      bestSim = sim;
      bestJ = i32(j);
    }
  }
  labels[i] = bestJ;
}
`;

function _isWebGpuAvailable() {
  return typeof navigator !== 'undefined'
      && navigator.gpu != null
      && typeof navigator.gpu.requestAdapter === 'function';
}

class GpuRecommender {
  /**
   * Try to create a GpuRecommender. Returns null if WebGPU is unavailable
   * or initialization fails for any reason. Never throws.
   */
  static async tryCreate(opts = {}) {
    if (!_isWebGpuAvailable()) {
      return null;
    }
    try {
      const adapter = await navigator.gpu.requestAdapter({
        powerPreference: opts.powerPreference || 'high-performance',
      });
      if (!adapter) return null;
      const device = await adapter.requestDevice();
      if (!device) return null;

      const dotModule = device.createShaderModule({ code: WGSL_DOT_KERNEL });
      const kmeansModule = device.createShaderModule({ code: WGSL_KMEANS_ASSIGN_KERNEL });

      const dotPipeline = await device.createComputePipelineAsync({
        layout: 'auto',
        compute: { module: dotModule, entryPoint: 'main' },
      });
      const kmeansPipeline = await device.createComputePipelineAsync({
        layout: 'auto',
        compute: { module: kmeansModule, entryPoint: 'main' },
      });

      const adapterInfo = adapter.info || {};
      const label = [adapterInfo.vendor, adapterInfo.architecture, adapterInfo.device]
        .filter(Boolean).join(' ').trim() || 'unknown-gpu';
      console.log('[GPU] WebGPU recommender initialized:', label);

      return new GpuRecommender(device, dotPipeline, kmeansPipeline, label);
    } catch (e) {
      console.warn('[GPU] WebGPU init failed, falling back to CPU:', e && e.message || e);
      return null;
    }
  }

  constructor(device, dotPipeline, kmeansPipeline, label) {
    this.device = device;
    this.dotPipeline = dotPipeline;
    this.kmeansPipeline = kmeansPipeline;
    this.label = label;
    this.disposed = false;

    // Storage layout
    this.count = 0;
    this.dim = 0;

    // Buffers — destroyed/recreated when count or dim change
    this.vectorsBuffer = null;

    // Per-query (dot product) state
    this.queryBuffer = null;
    this.dotOutputBuffer = null;
    this.dotReadBuffer = null;
    this.dotDimsBuffer = null;
    this.dotBindGroup = null;

    // Per-kmeans state — sized by numClusters; recycled across iterations
    this.centroidsBuffer = null;
    this.labelsBuffer = null;
    this.labelsReadBuffer = null;
    this.kmeansDimsBuffer = null;
    this.kmeansBindGroup = null;
    this.kmeansNumClusters = 0;

    // Watch for device loss (driver crash, GPU reset, browser policy).
    // Once lost, every method short-circuits to null so the CPU path is used.
    if (this.device && this.device.lost && typeof this.device.lost.then === 'function') {
      this.device.lost.then((info) => {
        console.warn('[GPU] WebGPU device lost:', info && info.message);
        this.disposed = true;
        this._freeBuffers();
      }).catch(() => { /* ignore */ });
    }
  }

  /** True iff GPU is alive and embeddings have been uploaded. */
  isReady() {
    return !this.disposed && this.vectorsBuffer != null && this.count > 0;
  }

  /**
   * Upload embeddings to GPU. Accepts an array of Float32Array (one per song);
   * null/missing entries are zero-filled (will produce sim=0).
   *
   * Returns true on success, false if upload failed (caller should fall back).
   */
  setEmbeddings(embeddings) {
    if (this.disposed) return false;
    this._freeBuffers();

    if (!embeddings || embeddings.length === 0) {
      this.count = 0;
      this.dim = 0;
      return true;
    }

    // Determine dim from first non-null entry.
    let dim = 0;
    for (let i = 0; i < embeddings.length; i++) {
      if (embeddings[i] && embeddings[i].length > 0) {
        dim = embeddings[i].length;
        break;
      }
    }
    if (dim === 0) {
      this.count = 0;
      this.dim = 0;
      return true;
    }

    const count = embeddings.length;
    const flat = new Float32Array(count * dim);
    for (let i = 0; i < count; i++) {
      const e = embeddings[i];
      if (e && e.length === dim) {
        flat.set(e, i * dim);
      }
      // missing entries: leave zero-filled
    }

    try {
      this.count = count;
      this.dim = dim;

      this.vectorsBuffer = this.device.createBuffer({
        size: flat.byteLength,
        usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST,
      });
      this.device.queue.writeBuffer(this.vectorsBuffer, 0, flat);

      // Dot-product per-query buffers
      this.queryBuffer = this.device.createBuffer({
        size: dim * 4,
        usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST,
      });
      this.dotOutputBuffer = this.device.createBuffer({
        size: count * 4,
        usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_SRC,
      });
      this.dotReadBuffer = this.device.createBuffer({
        size: count * 4,
        usage: GPUBufferUsage.MAP_READ | GPUBufferUsage.COPY_DST,
      });
      this.dotDimsBuffer = this.device.createBuffer({
        // 2 u32 (count, dim). 16-byte aligned for safety.
        size: 16,
        usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST,
      });
      this.device.queue.writeBuffer(this.dotDimsBuffer, 0, new Uint32Array([count, dim, 0, 0]));

      this.dotBindGroup = this.device.createBindGroup({
        layout: this.dotPipeline.getBindGroupLayout(0),
        entries: [
          { binding: 0, resource: { buffer: this.vectorsBuffer } },
          { binding: 1, resource: { buffer: this.queryBuffer } },
          { binding: 2, resource: { buffer: this.dotOutputBuffer } },
          { binding: 3, resource: { buffer: this.dotDimsBuffer } },
        ],
      });
      return true;
    } catch (e) {
      console.warn('[GPU] setEmbeddings failed:', e && e.message || e);
      this._freeBuffers();
      this.count = 0;
      this.dim = 0;
      return false;
    }
  }

  /**
   * Compute dot product of queryVec against all stored embeddings.
   * Returns Float32Array of length this.count (parallel to embeddings).
   * Returns null on any failure — caller falls back to CPU.
   */
  async computeAllSimilarities(queryVec) {
    if (!this.isReady()) return null;
    if (!queryVec || queryVec.length !== this.dim) return null;

    try {
      const queryF32 = queryVec instanceof Float32Array ? queryVec : new Float32Array(queryVec);
      this.device.queue.writeBuffer(this.queryBuffer, 0, queryF32);

      const encoder = this.device.createCommandEncoder();
      const pass = encoder.beginComputePass();
      pass.setPipeline(this.dotPipeline);
      pass.setBindGroup(0, this.dotBindGroup);
      pass.dispatchWorkgroups(Math.ceil(this.count / 64));
      pass.end();
      encoder.copyBufferToBuffer(this.dotOutputBuffer, 0, this.dotReadBuffer, 0, this.count * 4);
      this.device.queue.submit([encoder.finish()]);

      await this.dotReadBuffer.mapAsync(GPUMapMode.READ);
      const view = this.dotReadBuffer.getMappedRange();
      const data = new Float32Array(view.slice(0));
      this.dotReadBuffer.unmap();
      return data;
    } catch (e) {
      console.warn('[GPU] computeAllSimilarities failed:', e && e.message || e);
      return null;
    }
  }

  /**
   * K-means assignment step on GPU: for each song, find the centroid with the
   * highest dot product. Returns Int32Array of labels (length = this.count).
   * Returns null on any failure.
   *
   * `centroids` is array of Float32Array (length = numClusters, each of length this.dim).
   */
  async kmeansAssign(centroids, numClusters) {
    if (!this.isReady()) return null;
    if (!centroids || centroids.length < numClusters) return null;

    try {
      // Flatten centroids
      const flat = new Float32Array(numClusters * this.dim);
      for (let j = 0; j < numClusters; j++) {
        const c = centroids[j];
        if (!c || c.length !== this.dim) {
          console.warn('[GPU] kmeansAssign: centroid', j, 'has wrong dim');
          return null;
        }
        flat.set(c, j * this.dim);
      }

      // (Re)allocate buffers if cluster count changed
      if (this.kmeansNumClusters !== numClusters
          || this.centroidsBuffer == null) {
        if (this.centroidsBuffer) { try { this.centroidsBuffer.destroy(); } catch (_) { /* noop */ } }
        if (this.labelsBuffer) { try { this.labelsBuffer.destroy(); } catch (_) { /* noop */ } }
        if (this.labelsReadBuffer) { try { this.labelsReadBuffer.destroy(); } catch (_) { /* noop */ } }
        if (this.kmeansDimsBuffer) { try { this.kmeansDimsBuffer.destroy(); } catch (_) { /* noop */ } }

        this.centroidsBuffer = this.device.createBuffer({
          size: numClusters * this.dim * 4,
          usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST,
        });
        this.labelsBuffer = this.device.createBuffer({
          size: this.count * 4,
          usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_SRC,
        });
        this.labelsReadBuffer = this.device.createBuffer({
          size: this.count * 4,
          usage: GPUBufferUsage.MAP_READ | GPUBufferUsage.COPY_DST,
        });
        this.kmeansDimsBuffer = this.device.createBuffer({
          size: 16,
          usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST,
        });
        this.device.queue.writeBuffer(this.kmeansDimsBuffer, 0,
            new Uint32Array([this.count, this.dim, numClusters, 0]));

        this.kmeansBindGroup = this.device.createBindGroup({
          layout: this.kmeansPipeline.getBindGroupLayout(0),
          entries: [
            { binding: 0, resource: { buffer: this.vectorsBuffer } },
            { binding: 1, resource: { buffer: this.centroidsBuffer } },
            { binding: 2, resource: { buffer: this.labelsBuffer } },
            { binding: 3, resource: { buffer: this.kmeansDimsBuffer } },
          ],
        });
        this.kmeansNumClusters = numClusters;
      }

      this.device.queue.writeBuffer(this.centroidsBuffer, 0, flat);

      const encoder = this.device.createCommandEncoder();
      const pass = encoder.beginComputePass();
      pass.setPipeline(this.kmeansPipeline);
      pass.setBindGroup(0, this.kmeansBindGroup);
      pass.dispatchWorkgroups(Math.ceil(this.count / 64));
      pass.end();
      encoder.copyBufferToBuffer(this.labelsBuffer, 0, this.labelsReadBuffer, 0, this.count * 4);
      this.device.queue.submit([encoder.finish()]);

      await this.labelsReadBuffer.mapAsync(GPUMapMode.READ);
      const view = this.labelsReadBuffer.getMappedRange();
      const data = new Int32Array(view.slice(0));
      this.labelsReadBuffer.unmap();
      return data;
    } catch (e) {
      console.warn('[GPU] kmeansAssign failed:', e && e.message || e);
      return null;
    }
  }

  _freeBuffers() {
    const buffers = [
      this.vectorsBuffer, this.queryBuffer,
      this.dotOutputBuffer, this.dotReadBuffer, this.dotDimsBuffer,
      this.centroidsBuffer, this.labelsBuffer, this.labelsReadBuffer, this.kmeansDimsBuffer,
    ];
    for (const b of buffers) {
      if (b) { try { b.destroy(); } catch (_) { /* noop */ } }
    }
    this.vectorsBuffer = null;
    this.queryBuffer = null;
    this.dotOutputBuffer = null;
    this.dotReadBuffer = null;
    this.dotDimsBuffer = null;
    this.centroidsBuffer = null;
    this.labelsBuffer = null;
    this.labelsReadBuffer = null;
    this.kmeansDimsBuffer = null;
    this.dotBindGroup = null;
    this.kmeansBindGroup = null;
    this.kmeansNumClusters = 0;
  }

  dispose() {
    this.disposed = true;
    this._freeBuffers();
    try { this.device.destroy(); } catch (_) { /* noop */ }
  }
}

// Process-wide singleton. WebGPU adapter creation is expensive (~tens of ms)
// and must only happen once per page lifetime.
let _gpuSingleton = null;
let _gpuInitPromise = null;
let _gpuInitTried = false;

/**
 * Lazily create the process-wide GPU recommender. Returns the singleton on
 * subsequent calls. Returns null if WebGPU is unavailable or init fails.
 * Safe to call repeatedly — concurrent calls share the same in-flight promise.
 */
async function getOrInitGpuRecommender() {
  if (_gpuSingleton) return _gpuSingleton;
  if (_gpuInitPromise) return _gpuInitPromise;
  if (_gpuInitTried) return null;  // already failed once; don't retry
  _gpuInitTried = true;
  _gpuInitPromise = GpuRecommender.tryCreate().then((g) => {
    _gpuSingleton = g;
    _gpuInitPromise = null;
    return g;
  }).catch((e) => {
    console.warn('[GPU] init promise rejected:', e && e.message || e);
    _gpuInitPromise = null;
    return null;
  });
  return _gpuInitPromise;
}

/**
 * Fire-and-forget helper: upload embeddings to the GPU and attach to the
 * given Recommender. Silently no-ops if WebGPU is unavailable or fails.
 *
 * Safe to call immediately after `new Recommender(embeddings)` — the await
 * happens off the critical path; the recommender stays CPU-only until the
 * upload completes.
 */
async function attachGpuToRec(recInstance, embeddings) {
  if (!recInstance || typeof recInstance.attachGpu !== 'function') return;
  try {
    const gpu = await getOrInitGpuRecommender();
    if (!gpu) return;
    const ok = gpu.setEmbeddings(embeddings);
    if (ok) {
      recInstance.attachGpu(gpu);
    }
  } catch (e) {
    // GPU attach is best-effort. Failures leave the recommender CPU-only.
    console.warn('[GPU] attachGpuToRec failed:', e && e.message || e);
  }
}

export { GpuRecommender, getOrInitGpuRecommender, attachGpuToRec };
