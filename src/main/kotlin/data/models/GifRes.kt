package com.mashiverse.data.models

import com.mashiverse.images.playwright.combiners.getGifArgs
import com.mashiverse.images.playwright.combiners.getSmallGifArgs
import com.mashiverse.images.playwright.combiners.getXsGifArgs

enum class GifRes(val args: Map<String, Int>) {
    NORMAL(getGifArgs()),
    SMALL(getSmallGifArgs()),
    XS(getXsGifArgs()),
}