package com.mashiverse.configs


import kotlinx.coroutines.sync.Semaphore

// GIF
const val GIF_WIDTH = 552 * 1.75
const val GIF_HEIGHT = 736 * 1.75
const val GIF_TRAIT_WIDTH = 380 * 1.75
const val GIF_TRAIT_HEIGHT = 600 * 1.75
const val PLAYBACK_FPS = 15
const val DURATION_LIMIT_SEC = 6.66

const val LOWER_RES_GIF_WIDTH = (552 * 1.5).toInt()
const val LOWER_RES_GIF_HEIGHT = (736 * 1.5).toInt()
const val LOWER_RES_GIF_TRAIT_WIDTH = (380 * 1.5).toInt()
const val LOWER_RES_GIF_TRAIT_HEIGHT = (600 * 1.5).toInt()

// PNG
const val PNG_WIDTH = 552 * 2
const val PNG_HEIGHT = 736 * 2
const val PNG_TRAIT_WIDTH = 380 * 2
const val PNG_TRAIT_HEIGHT = 600 * 2

// Other
const val MAX_GENERATIONS = 10
val imageSemaphore = Semaphore(MAX_GENERATIONS)
val LAYER_ORDER = listOf(
    "background",
    "hair_back",
    "cape",
    "bottom",
    "upper",
    "head",
    "eyes",
    "hair_front",
    "hat",
    "left_accessory",
    "right_accessory"
)