package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/**
 * Modal form for creating and editing physical media entries.
 */
class PhysicalMediaForm(
    private val container: Element,
    private val onSave: suspend (PhysicalMedia) -> Unit,
    private val onCancel: () -> Unit
) {
    private var editingMedia: PhysicalMedia? = null
    private val imageUrls = mutableListOf<Pair<String, String?>>() // url, description
    private val alertDialog = AlertDialog(container)
    private var distributorSelector: DistributorSelector? = null
    private var selectedDistributor: String? = null

    /**
     * Show the form for creating a new physical media entry.
     */
    fun showCreate() {
        editingMedia = null
        imageUrls.clear()
        imageUrls.add("" to null) // Start with one empty image field
        selectedDistributor = null
        render()
    }

    /**
     * Show the form for editing an existing physical media entry.
     */
    fun showEdit(media: PhysicalMedia) {
        editingMedia = media
        imageUrls.clear()
        imageUrls.addAll(media.images.map { it.imageUrl to it.description })
        if (imageUrls.isEmpty()) {
            imageUrls.add("" to null) // Ensure at least one image field
        }
        selectedDistributor = media.distributor
        render()
    }

    /**
     * Close and hide the form.
     */
    fun close() {
        val modal = document.getElementById("physical-media-form-modal")
        modal?.remove()
    }

    private fun render() {
        // Remove existing modal if any
        close()

        container.append {
            div {
                id = "physical-media-form-modal"
                style = """
                    position: fixed;
                    top: 0;
                    left: 0;
                    width: 100%;
                    height: 100%;
                    background-color: rgba(0, 0, 0, 0.5);
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    z-index: 1000;
                """.trimIndent()

                div {
                    style = """
                        background-color: white;
                        padding: 30px;
                        border-radius: 8px;
                        max-width: 600px;
                        width: 90%;
                        max-height: 90vh;
                        overflow-y: auto;
                        box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
                    """.trimIndent()

                    h2 {
                        style = "margin-top: 0; color: #202124;"
                        +if (editingMedia != null) "Edit Physical Media" else "Add Physical Media"
                    }

                    renderFormFields()

                    div {
                        style = "margin-top: 24px; display: flex; gap: 12px; justify-content: flex-end;"

                        button {
                            style = """
                                padding: 10px 24px;
                                font-size: 14px;
                                cursor: pointer;
                                background-color: #f1f3f4;
                                color: #202124;
                                border: none;
                                border-radius: 4px;
                                font-weight: 500;
                                transition: background-color 0.2s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#e8eaed'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#f1f3f4'"
                            +"Cancel"
                            onClickFunction = {
                                close()
                                onCancel()
                            }
                        }

                        button {
                            id = "save-media-button"
                            style = """
                                padding: 10px 24px;
                                font-size: 14px;
                                cursor: pointer;
                                background-color: #1a73e8;
                                color: white;
                                border: none;
                                border-radius: 4px;
                                font-weight: 500;
                                transition: background-color 0.2s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                            +"Save"
                            onClickFunction = {
                                handleSave()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DIV.renderFormFields() {
        val media = editingMedia

        // Entry Letter
        div {
            style = "margin-bottom: 16px;"
            label {
                htmlFor = "form-entry-letter"
                style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                +"Entry Letter"
            }
            input(type = InputType.text) {
                id = "form-entry-letter"
                value = media?.entryLetter ?: ""
                placeholder = "A-Z (optional)"
                maxLength = "1"
                style = """
                    width: 100%;
                    padding: 10px 12px;
                    font-size: 14px;
                    border: 1px solid #dadce0;
                    border-radius: 4px;
                    box-sizing: border-box;
                    font-family: 'Roboto', arial, sans-serif;
                    text-transform: uppercase;
                """.trimIndent()
                attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                attributes["onblur"] = "this.style.borderColor='#dadce0'"
                attributes["oninput"] = "this.value = this.value.toUpperCase().replace(/[^A-Z]/g, '')"
            }
        }

        // Title
        inputField("Title", "physical-media-form-title", media?.title ?: "", "e.g., Lord of the Rings Trilogy Box Set", required = false)

        // Media Types (checkboxes)
        div {
            style = "margin-bottom: 16px;"
            label {
                style = "display: block; margin-bottom: 8px; font-weight: 500; font-size: 14px; color: #5f6368;"
                +"Media Types"
                span {
                    style = "color: #d93025;"
                    +" *"
                }
            }
            div {
                id = "media-types-container"
                style = "display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; padding: 12px; border: 1px solid #dadce0; border-radius: 4px; background-color: #f8f9fa;"

                val selectedTypes = media?.mediaTypes ?: emptyList()
                MediaType.values().forEach { type ->
                    val typeLabel = when (type) {
                        MediaType.VHS -> "VHS"
                        MediaType.DVD -> "DVD"
                        MediaType.BLURAY -> "Blu-ray"
                        MediaType.FOURK -> "4K"
                        MediaType.DIGITAL -> "Digital"
                    }

                    label {
                        style = """
                            display: flex;
                            align-items: center;
                            padding: 8px;
                            cursor: pointer;
                            border-radius: 4px;
                            transition: background-color 0.2s;
                            font-size: 14px;
                            color: #202124;
                        """.trimIndent()
                        attributes["onmouseover"] = "this.style.backgroundColor='#e8eaed'"
                        attributes["onmouseout"] = "this.style.backgroundColor='transparent'"

                        input(type = InputType.checkBox) {
                            name = "media-type"
                            value = type.name
                            checked = (type in selectedTypes)
                            style = "margin-right: 8px; cursor: pointer;"
                        }
                        +typeLabel
                    }
                }
            }
        }

        // Distributor Selector Container
        div {
            id = "distributor-selector-container"
            style = "position: relative;"
        }

        // Initialize the distributor selector after rendering
        mainScope.launch {
            distributorSelector = DistributorSelector(
                containerId = "distributor-selector-container",
                selectedDistributor = selectedDistributor,
                onDistributorChanged = { newDistributor ->
                    selectedDistributor = newDistributor
                }
            )
            distributorSelector?.render()
        }

        // Release Date
        div {
            style = "margin-bottom: 16px;"
            label {
                htmlFor = "physical-media-form-release-date"
                style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                +"Release Date"
            }
            input(type = InputType.date) {
                id = "physical-media-form-release-date"
                value = media?.releaseDate ?: ""
                style = """
                    width: 100%;
                    padding: 10px 12px;
                    font-size: 14px;
                    border: 1px solid #dadce0;
                    border-radius: 4px;
                    box-sizing: border-box;
                    font-family: 'Roboto', arial, sans-serif;
                """.trimIndent()
                attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                attributes["onblur"] = "this.style.borderColor='#dadce0'"
            }
        }

        // Bluray.com URL
        inputField("Blu-ray.com URL", "physical-media-form-bluray-url", media?.blurayComUrl ?: "", "https://www.blu-ray.com/...", required = false)

        // Library Location
        div {
            style = "margin-bottom: 16px;"
            label {
                htmlFor = "physical-media-form-location"
                style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                +"Library Location"
            }
            select {
                id = "physical-media-form-location"
                style = """
                    width: 100%;
                    padding: 10px 12px;
                    font-size: 14px;
                    border: 1px solid #dadce0;
                    border-radius: 4px;
                    box-sizing: border-box;
                    font-family: 'Roboto', arial, sans-serif;
                    background-color: white;
                """.trimIndent()
                attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                attributes["onblur"] = "this.style.borderColor='#dadce0'"

                option {
                    value = ""
                    selected = (media?.location == null)
                    +"Not specified"
                }
                option {
                    value = "Archive"
                    selected = (media?.location == "Archive")
                    +"Archive"
                }
                option {
                    value = "Shelf"
                    selected = (media?.location == "Shelf")
                    +"Shelf"
                }
            }
        }

        // Images Section
        h3 {
            style = "margin-top: 24px; margin-bottom: 12px; color: #5f6368; font-size: 16px;"
            +"Images"
            // Show count of existing images when editing
            val existingImageCount = imageUrls.count { it.first.isNotBlank() }
            if (existingImageCount > 0) {
                span {
                    style = """
                        margin-left: 8px;
                        padding: 2px 8px;
                        background-color: #e8f0fe;
                        color: #1a73e8;
                        border-radius: 12px;
                        font-size: 12px;
                        font-weight: 500;
                    """.trimIndent()
                    +"$existingImageCount loaded"
                }
            }
        }

        div {
            id = "images-container"
            style = "margin-bottom: 16px;"
        }

        button {
            id = "add-image-button"
            style = """
                padding: 8px 16px;
                font-size: 14px;
                cursor: pointer;
                background-color: #34a853;
                color: white;
                border: none;
                border-radius: 4px;
                font-weight: 500;
                transition: background-color 0.2s;
            """.trimIndent()
            attributes["onmouseover"] = "this.style.backgroundColor='#2d8e47'"
            attributes["onmouseout"] = "this.style.backgroundColor='#34a853'"
            +"+ Add Image"
            onClickFunction = {
                imageUrls.add("" to null)
                renderImages()
            }
        }

        // Initial render of images
        renderImages()
    }

    private fun renderImages() {
        val container = document.getElementById("images-container") ?: return
        container.innerHTML = ""

        container.append {
            imageUrls.forEachIndexed { index, (url, description) ->
                div {
                    style = """
                        margin-bottom: 12px;
                        padding: 12px;
                        border: 1px solid #dadce0;
                        border-radius: 4px;
                        background-color: #f8f9fa;
                    """.trimIndent()

                    div {
                        style = "display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px;"
                        strong {
                            style = "color: #5f6368; font-size: 13px;"
                            +"Image ${index + 1}"
                        }
                        if (imageUrls.size > 1) {
                            button {
                                style = """
                                    padding: 4px 12px;
                                    font-size: 12px;
                                    cursor: pointer;
                                    background-color: #ea4335;
                                    color: white;
                                    border: none;
                                    border-radius: 3px;
                                    font-weight: 500;
                                """.trimIndent()
                                +"Remove"
                                onClickFunction = {
                                    imageUrls.removeAt(index)
                                    renderImages()
                                }
                            }
                        }
                    }

                    input(type = InputType.url) {
                        id = "image-url-$index"
                        value = url
                        placeholder = "https://example.com/image.jpg"
                        style = """
                            width: 100%;
                            padding: 8px 10px;
                            font-size: 13px;
                            border: 1px solid #dadce0;
                            border-radius: 3px;
                            box-sizing: border-box;
                            margin-bottom: 6px;
                        """.trimIndent()
                    }

                    input(type = InputType.text) {
                        id = "image-desc-$index"
                        value = description ?: ""
                        placeholder = "Description (e.g., Front Cover, Back Cover)"
                        style = """
                            width: 100%;
                            padding: 8px 10px;
                            font-size: 13px;
                            border: 1px solid #dadce0;
                            border-radius: 3px;
                            box-sizing: border-box;
                        """.trimIndent()
                    }
                }
            }
        }
    }

    private fun DIV.inputField(
        labelText: String,
        id: String,
        value: String = "",
        placeholder: String = "",
        required: Boolean = true
    ) {
        div {
            style = "margin-bottom: 16px;"
            label {
                htmlFor = id
                style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                +labelText
                if (required) {
                    span {
                        style = "color: #d93025;"
                        +" *"
                    }
                }
            }
            input(type = InputType.text) {
                this.id = id
                this.value = value
                this.placeholder = placeholder
                style = """
                    width: 100%;
                    padding: 10px 12px;
                    font-size: 14px;
                    border: 1px solid #dadce0;
                    border-radius: 4px;
                    box-sizing: border-box;
                    font-family: 'Roboto', arial, sans-serif;
                """.trimIndent()
                attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                attributes["onblur"] = "this.style.borderColor='#dadce0'"
            }
        }
    }

    private fun handleSave() {
        try {
            // Collect selected media types from checkboxes
            val mediaTypeCheckboxes = document.querySelectorAll("input[name='media-type']:checked")
            val selectedMediaTypes = mutableListOf<MediaType>()
            for (i in 0 until mediaTypeCheckboxes.length) {
                val checkbox = mediaTypeCheckboxes.item(i) as? org.w3c.dom.HTMLInputElement
                if (checkbox != null) {
                    selectedMediaTypes.add(MediaType.valueOf(checkbox.value))
                }
            }

            if (selectedMediaTypes.isEmpty()) {
                alertDialog.show(
                    title = "Validation Error",
                    message = "Please select at least one media type!"
                )
                return
            }

            val entryLetter = (document.getElementById("form-entry-letter") as HTMLInputElement).value.trim()
                .takeIf { it.isNotBlank() }

            val title = (document.getElementById("physical-media-form-title") as HTMLInputElement).value.trim()
                .takeIf { it.isNotBlank() }

            val distributor = selectedDistributor?.trim()?.takeIf { it.isNotBlank() }
            val releaseDate = (document.getElementById("physical-media-form-release-date") as HTMLInputElement).value.trim()
                .takeIf { it.isNotBlank() }
            val blurayUrl = (document.getElementById("physical-media-form-bluray-url") as HTMLInputElement).value.trim()
                .takeIf { it.isNotBlank() }
            val location = (document.getElementById("physical-media-form-location") as HTMLSelectElement).value.trim()
                .takeIf { it.isNotBlank() }

            // Collect images
            val images = imageUrls.indices.mapNotNull { index ->
                val url = (document.getElementById("image-url-$index") as? HTMLInputElement)?.value?.trim()
                val desc = (document.getElementById("image-desc-$index") as? HTMLInputElement)?.value?.trim()
                if (!url.isNullOrBlank()) {
                    PhysicalMediaImage(url, desc.takeIf { !it.isNullOrBlank() })
                } else {
                    null
                }
            }

            val physicalMedia = PhysicalMedia(
                mediaTypes = selectedMediaTypes,
                entryLetter = entryLetter,
                title = title,
                distributor = distributor,
                releaseDate = releaseDate,
                blurayComUrl = blurayUrl,
                location = location,
                images = images,
                id = editingMedia?.id
            )

            mainScope.launch {
                try {
                    onSave(physicalMedia)
                    close()
                } catch (e: Exception) {
                    alertDialog.show(
                        title = "Error",
                        message = "Error saving physical media: ${e.message}"
                    )
                }
            }
        } catch (e: Exception) {
            alertDialog.show(
                title = "Error",
                message = "Error processing form: ${e.message}"
            )
        }
    }
}
