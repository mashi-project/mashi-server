package com.mashiverse.images.playwright.combiners

import com.mashiverse.configs.GIF_HEIGHT
import com.mashiverse.configs.GIF_TRAIT_HEIGHT
import com.mashiverse.configs.GIF_TRAIT_WIDTH
import com.mashiverse.configs.GIF_WIDTH
import com.mashiverse.configs.LOWER_RES_GIF_HEIGHT
import com.mashiverse.configs.LOWER_RES_GIF_TRAIT_HEIGHT
import com.mashiverse.configs.LOWER_RES_GIF_TRAIT_WIDTH
import com.mashiverse.configs.LOWER_RES_GIF_WIDTH
import com.mashiverse.configs.PNG_HEIGHT
import com.mashiverse.configs.PNG_TRAIT_HEIGHT
import com.mashiverse.configs.PNG_TRAIT_WIDTH
import com.mashiverse.configs.PNG_WIDTH
import com.microsoft.playwright.Page

fun getGifArgs() = mapOf(
    "IMAGE_WIDTH" to GIF_WIDTH,
    "IMAGE_HEIGHT" to GIF_HEIGHT,
    "TRAIT_WIDTH" to GIF_TRAIT_WIDTH,
    "TRAIT_HEIGHT" to GIF_TRAIT_HEIGHT
)

fun getLowerResGifArgs() = mapOf(
    "IMAGE_WIDTH" to LOWER_RES_GIF_WIDTH,
    "IMAGE_HEIGHT" to LOWER_RES_GIF_HEIGHT,
    "TRAIT_WIDTH" to LOWER_RES_GIF_TRAIT_WIDTH,
    "TRAIT_HEIGHT" to LOWER_RES_GIF_TRAIT_HEIGHT
)

fun getPngArgs() = mapOf(
    "IMAGE_WIDTH" to PNG_WIDTH,
    "IMAGE_HEIGHT" to PNG_HEIGHT,
    "TRAIT_WIDTH" to PNG_TRAIT_WIDTH,
    "TRAIT_HEIGHT" to PNG_TRAIT_HEIGHT
)

fun prepareHtml(
    urls: List<String>,
    width: Int,
    height: Int,
): String {
    val imageTags = urls.mapIndexed { i, url ->
        """
            <div style="position:absolute; inset:0; display:flex; align-items:center; justify-content:center; z-index:$i;">
                <img src="$url" style="width:100%; height:100%; object-fit:contain;" />
            </div>
            """.trimIndent()
    }.joinToString("\n")

    return """
        <!DOCTYPE html>
        <html>
            <head>
                <style>
                    * {
                        box-sizing: border-box;
                    }
                    img, canvas {
                        /* Force smooth scaling across engines */
                        image-rendering: auto;
                        image-rendering: smooth;
                    }
                </style>
            </head>
            <body style="margin:0; width:${width}px; height:${height}px; background:transparent; overflow:hidden;">
                $imageTags
            </body>
        </html>
        """.trimIndent()
}

fun preparePage(
    page: Page,
    evaluateArgs: Map<String, Int>
) {
    page.evaluate(
        """
        (args) => {
            const imgs = Array.from(document.images);
            return Promise.all(
                imgs.map(img => img.complete ? Promise.resolve() : new Promise(res => img.onload = res))
            ).then(() => {
                const padX = (args.IMAGE_WIDTH - args.TRAIT_WIDTH) / 2;
                const padY = (args.IMAGE_HEIGHT - args.TRAIT_HEIGHT) / 2;
                document.querySelectorAll('img').forEach(img => {
                    const ratio = img.naturalWidth / img.naturalHeight;
                    if (Math.abs(ratio - 0.75) > 0.01) {
                        img.parentElement.style.padding = `${'$'}{padY}px ${'$'}{padX}px`;
                    }
                });
            });
        }
        """.trimIndent(), evaluateArgs
    )
}