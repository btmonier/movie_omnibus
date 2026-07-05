package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
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
        imageUrls.addAll(media.displayImages().map { it.imageUrl to it.description })
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

        // Bluray.com URL with inline "Fetch details" button
        div {
            style = "margin-bottom: 16px;"
            label {
                htmlFor = "physical-media-form-bluray-url"
                style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                +"Blu-ray.com URL"
            }
            div {
                style = "display: flex; gap: 8px; align-items: stretch;"
                input(type = InputType.text) {
                    id = "physical-media-form-bluray-url"
                    value = media?.blurayComUrl ?: ""
                    placeholder = "https://www.blu-ray.com/..."
                    style = """
                        flex: 1;
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
                button {
                    id = "bluray-fetch-button"
                    type = ButtonType.button
                    style = """
                        padding: 10px 16px;
                        font-size: 14px;
                        cursor: pointer;
                        background-color: #1a73e8;
                        color: white;
                        border: none;
                        border-radius: 4px;
                        font-weight: 500;
                        white-space: nowrap;
                        transition: background-color 0.2s;
                    """.trimIndent()
                    attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                    attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                    +"Fetch details"
                    onClickFunction = {
                        handleFetchFromBluRay()
                    }
                }
            }
            div {
                id = "bluray-fetch-status"
                style = "display: none; margin-top: 6px; font-size: 12px;"
            }
            p {
                style = "color: #80868b; font-size: 12px; margin-top: 6px; margin-bottom: 0;"
                +"Paste a blu-ray.com release URL and click Fetch details to auto-fill the fields below."
            }
        }

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

    /**
     * Returns true when the image URL is hosted by blu-ray.com (cover art is
     * served from images.static-bluray.com).
     */
    private fun isBluRayImageUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        if (u.isEmpty()) return false
        return u.contains("blu-ray.com") || u.contains("static-bluray.com")
    }

    /**
     * Reads the current image URL/description inputs from the DOM back into
     * [imageUrls] so a re-render does not lose unsaved edits.
     */
    private fun syncImagesFromDom() {
        for (index in imageUrls.indices) {
            val url = (document.getElementById("image-url-$index") as? HTMLInputElement)?.value
                ?: imageUrls[index].first
            val desc = (document.getElementById("image-desc-$index") as? HTMLInputElement)?.value
            imageUrls[index] = url to desc?.takeIf { it.isNotBlank() }
        }
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
                                    syncImagesFromDom()
                                    imageUrls.removeAt(index)
                                    renderImages()
                                }
                            }
                        }
                    }

                    // Preview thumbnail for blu-ray.com images
                    if (url.isNotBlank() && isBluRayImageUrl(url)) {
                        div {
                            style = "margin-bottom: 8px;"
                            img {
                                src = url
                                alt = description ?: "Image preview"
                                style = """
                                    max-width: 120px;
                                    max-height: 160px;
                                    border-radius: 4px;
                                    border: 1px solid #dadce0;
                                    background-color: #fff;
                                """.trimIndent()
                                attributes["onerror"] = "this.style.display='none'"
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
                        // Refresh the preview when the URL changes (fires on blur).
                        onChangeFunction = {
                            syncImagesFromDom()
                            renderImages()
                        }
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

    /**
     * Scrape a blu-ray.com URL and prefill the form. Existing user-entered values
     * are preserved; only empty fields are filled.
     */
    private fun handleFetchFromBluRay() {
        val urlInput = document.getElementById("physical-media-form-bluray-url") as? HTMLInputElement
        val url = urlInput?.value?.trim() ?: ""

        if (url.isBlank()) {
            alertDialog.show(
                title = "Validation Error",
                message = "Please enter a Blu-ray.com URL first."
            )
            return
        }

        val validUrlPattern = Regex("""^https?://(www\.)?blu-ray\.com/(movies|dvd)/[^/]+/\d+/?""", RegexOption.IGNORE_CASE)
        if (!validUrlPattern.containsMatchIn(url)) {
            alertDialog.show(
                title = "Invalid URL",
                message = "Please enter a valid blu-ray.com release URL.\n\nExamples:\nhttps://www.blu-ray.com/movies/Invaders-from-Mars-4K-Blu-ray/336476/\nhttps://www.blu-ray.com/dvd/1-Ichi-DVD/91279/"
            )
            return
        }

        val fetchButton = document.getElementById("bluray-fetch-button") as? HTMLButtonElement
        fetchButton?.disabled = true
        setFetchStatus("Fetching details from Blu-ray.com...", "#5f6368")

        mainScope.launch {
            try {
                val response = scrapeBluRayUrl(url)
                if (response.success && response.physicalMedia != null) {
                    applyScrapedData(response.physicalMedia)
                    setFetchStatus("Details loaded. Review and edit before saving.", "#188038")
                } else {
                    setFetchStatus(null, null)
                    alertDialog.show(
                        title = "Fetch Failed",
                        message = response.error ?: "Could not fetch details. Please try again or enter details manually."
                    )
                }
            } catch (e: Exception) {
                setFetchStatus(null, null)
                alertDialog.show(
                    title = "Error",
                    message = "An error occurred while fetching: ${e.message}"
                )
            } finally {
                fetchButton?.disabled = false
            }
        }
    }

    /**
     * Fills empty form fields with scraped values, leaving any user-entered
     * values untouched.
     */
    private fun applyScrapedData(scraped: PhysicalMedia) {
        // Media types: only fill when the user has not selected any yet.
        val checkboxes = document.querySelectorAll("input[name='media-type']")
        var anyChecked = false
        for (i in 0 until checkboxes.length) {
            val checkbox = checkboxes.item(i) as? HTMLInputElement
            if (checkbox?.checked == true) anyChecked = true
        }
        if (!anyChecked && scraped.mediaTypes.isNotEmpty()) {
            val scrapedNames = scraped.mediaTypes.map { it.name }.toSet()
            for (i in 0 until checkboxes.length) {
                val checkbox = checkboxes.item(i) as? HTMLInputElement ?: continue
                checkbox.checked = checkbox.value in scrapedNames
            }
        }

        // Title
        val titleInput = document.getElementById("physical-media-form-title") as? HTMLInputElement
        if (titleInput != null && titleInput.value.isBlank() && !scraped.title.isNullOrBlank()) {
            titleInput.value = scraped.title
        }

        // Release date
        val releaseDateInput = document.getElementById("physical-media-form-release-date") as? HTMLInputElement
        if (releaseDateInput != null && releaseDateInput.value.isBlank() && !scraped.releaseDate.isNullOrBlank()) {
            releaseDateInput.value = scraped.releaseDate
        }

        // Distributor (DistributorSelector has no public setter, so set the field + form var)
        if (selectedDistributor.isNullOrBlank() && !scraped.distributor.isNullOrBlank()) {
            selectedDistributor = scraped.distributor
            val distributorInput = document.getElementById("distributor-selector-container-input") as? HTMLInputElement
            distributorInput?.value = scraped.distributor
        }

        // Images: only fill when no image URL has been entered yet.
        val hasExistingImage = imageUrls.any { it.first.isNotBlank() }
        if (!hasExistingImage && scraped.images.isNotEmpty()) {
            imageUrls.clear()
            imageUrls.addAll(scraped.images.map { it.imageUrl to it.description })
            if (imageUrls.isEmpty()) imageUrls.add("" to null)
            renderImages()
        }
    }

    private fun setFetchStatus(message: String?, color: String?) {
        val status = document.getElementById("bluray-fetch-status") as? HTMLElement ?: return
        if (message == null) {
            status.style.display = "none"
            status.textContent = ""
        } else {
            status.style.display = "block"
            status.style.color = color ?: "#5f6368"
            status.textContent = message
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

            // Collect images from the in-memory list (synced from the DOM). Using
            // syncImagesFromDom keeps any user edits while falling back to the
            // originally loaded value when a DOM node can't be read, so an
            // existing (e.g. GCS signed) image URL is never silently dropped.
            syncImagesFromDom()
            val images = imageUrls.mapNotNull { (url, desc) ->
                val trimmed = url.trim()
                if (trimmed.isNotBlank()) {
                    PhysicalMediaImage(trimmed, desc?.takeIf { it.isNotBlank() })
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
