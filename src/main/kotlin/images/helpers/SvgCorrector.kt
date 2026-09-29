package com.mashiverse.images.helpers

import nu.pattern.OpenCV
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

object SvgCorrector {

    init {
        OpenCV.loadLocally()
    }

    fun processSvg(inputBytes: ByteArray): ByteArray {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true

            // Disable external DTDs to prevent W3C rate-limiting (HTTP 429)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            // Optional additional security hardening against XML External Entity (XXE) injections:
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(inputBytes))
        val root = doc.documentElement

        // --- 0. Parse CSS <style> blocks ---
        val cssMap = mutableMapOf<String, String>()
        val styleNodes = doc.getElementsByTagName("style")
        for (i in 0 until styleNodes.length) {
            val content = styleNodes.item(i).textContent
            val cleanContent = content.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            val blockRegex = Regex("([^\\{]+)\\{([^}]+)\\}")
            blockRegex.findAll(cleanContent).forEach { match ->
                val selectors = match.groupValues[1]
                val rules = match.groupValues[2]
                val classRegex = Regex("\\.([a-zA-Z0-9_\\-]+)")
                classRegex.findAll(selectors).forEach { clsMatch ->
                    cssMap[clsMatch.groupValues[1]] = rules
                }
            }
        }

        // --- 1. Map every Mask ID to its calculated path data ---
        val maskDefinitions = mutableMapOf<String, String>()
        val masks = doc.getElementsByTagNameNS("http://www.w3.org/2000/svg", "mask")

        for (i in 0 until masks.length) {
            val maskElem = masks.item(i) as Element
            val maskId = maskElem.getAttribute("id") ?: continue
            var combinedPathD = ""

            val images = maskElem.getElementsByTagNameNS("http://www.w3.org/2000/svg", "image")
            for (j in 0 until images.length) {
                val imgElem = images.item(j) as Element
                var href = imgElem.getAttributeNS("http://www.w3.org/1999/xlink", "href")
                if (href.isEmpty()) href = imgElem.getAttribute("href")

                if (!href.contains("base64,")) continue

                val img = try {
                    val b64Str = href.substringAfter("base64,").replace("\\s".toRegex(), "")
                    val decodedBytes = Base64.getDecoder().decode(b64Str)
                    val matOfByte = MatOfByte(*decodedBytes)
                    Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_UNCHANGED)
                } catch (e: Exception) {
                    continue
                }

                if (img == null || img.empty()) continue

                val maskBits = Mat()
                // Match Python logic: Extract ONLY the alpha channel if 4 channels exist
                if (img.channels() == 4) {
                    Core.extractChannel(img, maskBits, 3)
                } else {
                    Imgproc.cvtColor(img, maskBits, Imgproc.COLOR_BGR2GRAY)
                }

                // Match Python logic: Raise threshold to 127.0 to filter out fringe gaps cleanly
                val thresh = Mat()
                Imgproc.threshold(maskBits, thresh, 127.0, 255.0, Imgproc.THRESH_BINARY)

                val contours = mutableListOf<MatOfPoint>()
                val hierarchy = Mat()

                // Match Python logic: Use RETR_EXTERNAL to get the outer shell boundary
                Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                val imgX = imgElem.getAttribute("x").toDoubleOrNull() ?: 0.0
                val imgY = imgElem.getAttribute("y").toDoubleOrNull() ?: 0.0

                for (contour in contours) {
                    if (Imgproc.contourArea(contour) < 10) continue // Match Python's threshold < 10
                    val points = contour.toArray()
                    if (points.isEmpty()) continue

                    combinedPathD += "M ${points[0].x + imgX} ${points[0].y + imgY} "
                    for (k in 1 until points.size) {
                        combinedPathD += "L ${points[k].x + imgX} ${points[k].y + imgY} "
                    }
                    combinedPathD += "Z "
                }

                // Native memory lifecycle management
                maskBits.release()
                thresh.release()
                hierarchy.release()
                img.release()
            }

            if (combinedPathD.isNotBlank()) {
                maskDefinitions["url(#$maskId)"] = combinedPathD.trim()
            }
        }

        // --- 2. Convert masks into clipPaths instead of destructive replacement ---
        val allNodes = root.getElementsByTagName("*")
        val nodesToProcessMasks = mutableListOf<Element>()
        for (i in 0 until allNodes.length) {
            val node = allNodes.item(i) as Element
            if (node.hasAttribute("mask") && maskDefinitions.containsKey(node.getAttribute("mask"))) {
                nodesToProcessMasks.add(node)
            }
        }

        var defsElement = root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "defs").item(0) as? Element
        if (defsElement == null) {
            defsElement = doc.createElementNS("http://www.w3.org/2000/svg", "defs")
            root.insertBefore(defsElement, root.firstChild)
        }

        for (child in nodesToProcessMasks) {
            val maskAttr = child.getAttribute("mask")
            val pathD = maskDefinitions[maskAttr] ?: continue

            child.removeAttribute("mask")

            val uniqueClipId = "vector-clip-${UUID.randomUUID().toString().take(8)}"

            val clipPathNode = doc.createElementNS("http://www.w3.org/2000/svg", "clipPath").apply {
                setAttribute("id", uniqueClipId)
            }

            val pathNode = doc.createElementNS("http://www.w3.org/2000/svg", "path").apply {
                setAttribute("d", pathD)
                setAttribute("fill-rule", "evenodd")
            }
            clipPathNode.appendChild(pathNode)
            defsElement.appendChild(clipPathNode)

            child.setAttribute("clip-path", "url(#$uniqueClipId)")
        }

        // --- 3. Replace <use> tags with actual elements ---
        val defsMap = mutableMapOf<String, Element>()
        val elementsWithId = root.getElementsByTagName("*")
        for (i in 0 until elementsWithId.length) {
            val el = elementsWithId.item(i) as Element
            val id = el.getAttribute("id")
            if (id.isNotEmpty()) defsMap["#$id"] = el
        }

        val useElements = root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "use")
        val useList = mutableListOf<Element>()
        for (i in 0 until useElements.length) useList.add(useElements.item(i) as Element)

        for (useElem in useList) {
            var href = useElem.getAttributeNS("http://www.w3.org/1999/xlink", "href")
            if (href.isEmpty()) href = useElem.getAttribute("href")

            val targetNode = defsMap[href]
            if (targetNode != null && useElem.parentNode != null) {
                val referencedNode = targetNode.cloneNode(true) as Element

                val ux = useElem.getAttribute("x").ifEmpty { "0" }
                val uy = useElem.getAttribute("y").ifEmpty { "0" }

                if (ux != "0" || uy != "0") {
                    val existTr = referencedNode.getAttribute("transform")
                    referencedNode.setAttribute("transform", "translate($ux, $uy) $existTr".trim())
                }

                val useAttrs = useElem.attributes
                for (k in 0 until useAttrs.length) {
                    val attr = useAttrs.item(k)
                    val name = attr.nodeName
                    if (name !in listOf("href", "xlink:href", "x", "y", "id")) {
                        referencedNode.setAttribute(name, attr.nodeValue)
                    }
                }

                useElem.parentNode.replaceChild(referencedNode, useElem)
            }
        }

        // --- 4. Cleanup defs ---
        val defsList = root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "defs")
        for (i in 0 until defsList.length) {
            val defs = defsList.item(i) as Element

            val childImages = defs.getElementsByTagNameNS("http://www.w3.org/2000/svg", "image")
            for (k in childImages.length - 1 downTo 0) {
                val img = childImages.item(k)
                if (img.parentNode == defs) defs.removeChild(img)
            }

            val childMasks = defs.getElementsByTagNameNS("http://www.w3.org/2000/svg", "mask")
            for (k in childMasks.length - 1 downTo 0) {
                val mask = childMasks.item(k)
                if (mask.parentNode == defs) defs.removeChild(mask)
            }
        }

        val clipPaths = root.getElementsByTagNameNS("http://www.w3.org/2000/svg", "clipPath")
        for (i in 0 until clipPaths.length) {
            (clipPaths.item(i) as Element).removeAttribute("clipPathUnits")
        }

        // --- 5. Finalize & Transform ---
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        }

        val outStream = ByteArrayOutputStream()
        transformer.transform(DOMSource(doc), StreamResult(outStream))
        return outStream.toByteArray()
    }
}