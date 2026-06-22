package com.isaivazhi.app.engine

data class RecommendationBlendInfo(
    val weights: Recommender.BlendWeights,
    val label: String,
)
