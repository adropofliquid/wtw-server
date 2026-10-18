package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.domain.LibraryItem

/**
 * Library vibe tags (Vocab.VIBES) from what an item already says about itself: garment type, name, description,
 * keywords, brand, colours, formality and polish. Rule-based so a re-seed gives the same tags. Every item gets
 * at least one vibe.
 */
object VibeTagger {

    fun tag(item: LibraryItem): List<String> {
        val a = item.attributes.orEmpty()
        val tags = (a["tags"] as? List<*>).orEmpty().map { it.toString().lowercase() }
        val garment = tags.firstOrNull { it.startsWith("garment_type:") }?.substringAfter(':')
            ?: item.fileName?.substringBeforeLast('.')?.replace('_', '-') ?: ""
        // "Dr. Martens" → "dr-martens", the same form as the `brand:` tag and the lists below.
        val brand = (item.brand ?: tags.firstOrNull { it.startsWith("brand:") }?.substringAfter(':') ?: "")
            .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val text = listOf(item.name, item.description, garment.replace('-', ' '), item.keywords.joinToString(" "))
            .joinToString(" ").lowercase()
        val colours = (a["colors"] as? List<*>).orEmpty().map { it.toString().lowercase() }
        val formality = a["formality"] as? String
        val polish = a["polish"] as? String

        val out = linkedSetOf<String>()
        val sporty = garment in SPORTY_GARMENTS || brand in SPORTY_BRANDS || SPORTY_WORDS.any { it in text }
        val street = garment in STREET_GARMENTS || (brand in STREET_BRANDS && item.category != "accessories" || brand in STREET_ONLY_BRANDS) ||
            (STREET_WORDS.any { it in text } && garment !in SMART_CASUAL_GARMENTS && garment !in CLASSIC_GARMENTS && "knit" !in garment)
        if (sporty) out += "Sporty"
        if (street) out += "Streetwear"
        if (garment in CLASSIC_GARMENTS || formality in setOf("business", "cocktail") || polish == "polished" || CLASSIC_WORDS.any { it in text }) out += "Classic"
        if (garment in SMART_CASUAL_GARMENTS || formality == "smart_casual" || polish == "considered") out += "Smart casual"

        val colourful = colours.any { c -> BRIGHT.any { it in c } } || PATTERN_WORDS.any { it in text } ||
            (colours.isEmpty() && BRIGHT.any { Regex("\\b$it\\b").containsMatchIn(text.replace("olive green", "olive")) })
        if (colourful) out += "Colourful"
        val neutral = colours.isNotEmpty() && colours.all { c -> NEUTRAL.any { it in c } && BRIGHT.none { it in c } } ||
            colours.isEmpty() && !colourful
        // Minimal: quiet pieces only. Neutral, plain, no logo brand, not sporty or street.
        if (neutral && !colourful && !sporty && !street && formality != "lounge" && brand !in LOGO_BRANDS &&
            garment !in LOUD_GARMENTS && PLAIN_BREAKERS.none { it in text }
        ) out += "Minimal"

        // Nothing fired: a logo piece reads street; anything else is a plain basic.
        if (out.isEmpty()) out += if (brand in LOGO_BRANDS) "Streetwear" else "Minimal"
        return Vocab.VIBES.filter { it in out }
    }

    private val SPORTY_GARMENTS = setOf(
        "running-shoes", "white-running-shoes", "running-shorts", "leggings", "flared-leggings", "sports-bra", "sports-t-shirt",
        "track-jacket", "track-pants", "joggers", "athletic-tank-top", "cycling-shorts", "basketball-shorts", "basketball-jersey",
        "football-shirt", "digital-sports-watch", "smartwatch", "pleated-tennis-skirt", "windbreaker", "sweat-shorts",
        "straight-leg-sweatpants", "hiking-boots", "half-zip-fleece", "fleece-jacket",
    )
    private val SPORTY_BRANDS = setOf("lululemon", "under-armour", "asics", "hoka", "patagonia")
    private val SPORTY_WORDS = listOf("running", "athletic", "moisture-wicking", "gym", "workout", "training")

    private val STREET_GARMENTS = setOf(
        "hoodie", "zip-up-hoodie", "oversized-hoodie", "cropped-hoodie", "crew-neck-sweatshirt", "graphic-t-shirt",
        "oversized-t-shirt", "cargo-trousers", "cargo-shorts", "parachute-trousers", "baggy-jeans", "puffer-jacket",
        "cropped-puffer-jacket", "long-puffer-coat", "bomber-jacket", "varsity-jacket", "baseball-cap", "bucket-hat",
        "beanie", "high-top-canvas-trainers", "chunky-trainers", "platform-trainers", "basketball-jersey", "bum-bag",
        "nylon-crossbody-bag", "slide-sandals", "oversized-denim-jacket", "track-jacket", "joggers", "ringer-t-shirt",
        "utility-jacket", "work-jacket", "lace-up-work-boots", "chunky-lug-sole-boots",
        "flannel-check-shirt", "plaid-mini-skirt",
    )
    /** Street brands, but not for their plain accessories (socks, a wallet). */
    private val STREET_BRANDS = setOf(
        "nike", "adidas", "puma", "reebok", "jordan", "vans", "converse", "fila", "champion", "the-north-face", "new-balance", "dr-martens",
    )
    private val STREET_ONLY_BRANDS = setOf("stussy", "carhartt", "new-era")
    private val STREET_WORDS = listOf("graphic", "oversized", "cargo", "drop-shoulder", "chunky")

    private val CLASSIC_GARMENTS = setOf(
        "blazer", "oxford-button-down-shirt", "poplin-dress-shirt", "tailored-trousers", "chinos", "trench-coat", "wool-overcoat",
        "long-wool-coat", "penny-loafers", "oxford-shoes", "derby-shoes", "chelsea-boots", "cable-knit-jumper",
        "crew-neck-knit-jumper", "v-neck-knit-jumper", "roll-neck-jumper", "leather-belt", "leather-strap-watch",
        "metal-bracelet-watch", "silk-tie", "pearl-necklace", "pearl-stud-earrings", "pencil-skirt",
        "breton-striped-long-sleeve-top", "polo-shirt", "wax-jacket", "harrington-jacket", "leather-gloves", "signet-ring",
        "wool-scarf", "leather-tote-bag", "leather-messenger-bag", "pointed-court-heels", "ballet-flats", "wrap-dress",
        "shirt-dress", "suede-loafers", "desert-boots", "quilted-jacket", "leather-wallet", "top-handle-handbag",
        "check-scarf", "striped-t-shirt", "striped-fitted-long-sleeve-top",
    )
    private val CLASSIC_WORDS = listOf("tailored", "pressed", "dress shirt", "dress shoes", "pearl")

    private val SMART_CASUAL_GARMENTS = setOf(
        "chinos", "polo-shirt", "knit-polo-shirt", "quarter-zip-knit", "half-zip-knit-jumper", "shacket", "chore-jacket",
        "linen-shirt", "button-up-shirt", "short-sleeve-camp-collar-shirt", "suede-loafers", "leather-trainers",
        "desert-boots", "chelsea-boots", "oversized-blazer", "cardigan", "chunky-knit-cardigan", "corduroy-trousers",
        "corduroy-jacket", "wide-leg-trousers", "linen-trousers", "knit-midi-dress", "shirt-dress", "blouse", "satin-blouse",
        "mules", "ballet-flats", "denim-shirt", "suede-jacket", "flared-trousers", "chunky-loafers", "chino-shorts",
        "leather-biker-jacket", "cropped-leather-jacket", "heeled-ankle-boots", "slip-skirt", "satin-midi-skirt",
    )

    /** Colour words that read as colour, not neutral. Navy, denim and olive count as neutral. */
    private val BRIGHT = listOf(
        "pink", "red", "burgundy", "yellow", "emerald", "sage", "forest green", "royal blue", "light blue", "purple",
        "lilac", "orange", "coral", "teal", "turquoise", "fuchsia", "magenta", "green", "rose gold",
    )
    private val NEUTRAL = listOf(
        "black", "white", "grey", "gray", "navy", "cream", "beige", "camel", "charcoal", "stone", "sand", "taupe",
        "oatmeal", "natural", "tan", "brown", "chestnut", "khaki", "olive", "nude", "wheat", "ivory", "off-white",
        "indigo", "denim", "mid-blue", "light wash", "light-wash", "silver", "gold", "champagne", "tortoiseshell",
    )
    private val PATTERN_WORDS = listOf("floral", "sequin", "tie-dye", "colour-block", "color-block", "multicolour", "rainbow")
    private val LOGO_BRANDS = setOf(
        "nike", "adidas", "jordan", "puma", "fila", "champion", "tommy-hilfiger", "ralph-lauren", "lacoste", "stussy",
        "carhartt", "the-north-face", "new-era", "under-armour", "reebok", "calvin-klein", "michael-kors", "crocs",
    )
    /** Plain-looking but not minimal. */
    private val LOUD_GARMENTS = setOf("graphic-t-shirt", "varsity-jacket", "basketball-jersey", "football-shirt", "sequin-mini-skirt")
    private val PLAIN_BREAKERS = listOf("graphic", "logo", "print", "check", "plaid", "distressed", "stripe", "sequin", "chunky", "embroider")
}
