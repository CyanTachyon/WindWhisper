package moe.tachyon.windwhisper.utils

import moe.tachyon.windwhisper.logger.WindWhisperLogger
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

object DocumentConversion
{
    private val logger = WindWhisperLogger.getLogger<DocumentConversion>()

    fun documentToImages(
        document: ByteArray,
        dpi: Int = 300
    ): List<BufferedImage>?
    {
        runCatching()
        {
            return listOf(ImageIO.read(document.inputStream())!!)
        }
        runCatching()
        {
            return pdfToImages(document, dpi)
        }
        return null // todo pptx/docx/xlsx to image
    }

    fun pdfToImages(
        pdf: ByteArray,
        dpi: Int = 300
    ): List<BufferedImage>
    {
        PDDocument.load(pdf).use()
        { document ->
            val images = mutableListOf<BufferedImage>()
            val renderer = PDFRenderer(document)
            for (pageIndex in 0 until document.numberOfPages)
            {
                val image = renderer.renderImageWithDPI(pageIndex, dpi.toFloat())
                if (image != null) images.add(image)
            }
            return images
        }
    }
}