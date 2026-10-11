package com.adropofliquid.wtw.service

import com.adropofliquid.wtw.client.storage.Bucket
import com.adropofliquid.wtw.client.storage.Keys
import com.adropofliquid.wtw.client.storage.ObjectStorage
import com.adropofliquid.wtw.config.WtwProperties
import com.adropofliquid.wtw.domain.LibraryItem
import com.adropofliquid.wtw.repository.LibraryItemRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.system.exitProcess

/**
 * One-off Library seed (brief 5.4): `java -jar app.jar --seed-library` with LIBRARY_SEED_DIR holding
 * `starter-items.json` + starter PNGs (root or `onboarding-clothing-items/`), `catalog.json` + `cutout/` PNGs.
 * Upserts `library_item` by id and uploads each image as WebP to `library/{id}.webp`. Idempotent: an existing
 * object is not uploaded again and an unchanged row is not rewritten. Exits the JVM (0 ok, 1 failed).
 * Starter PNGs missing locally are fetched from the old S3 bucket only when OLD_S3_ACCESS_KEY/OLD_S3_SECRET are set.
 */
@Component
class LibrarySeedRunner(
    private val props: WtwProperties,
    private val repo: LibraryItemRepository,
    private val storage: ObjectStorage,
    private val images: ImageProcessor,
    private val mapper: ObjectMapper,
    private val env: Environment,
    private val ctx: ApplicationContext,
) : ApplicationRunner {

    private class Tally {
        var inserted = 0; var updated = 0; var unchanged = 0
        var uploaded = 0; var skipped = 0
        val missing = mutableListOf<String>()
    }

    override fun run(args: ApplicationArguments) {
        if (args.sourceArgs.none { it.startsWith("--seed-library") }) return
        val code = try {
            seed()
            0
        } catch (e: Exception) {
            System.err.println("Library seed FAILED: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            1
        }
        exitProcess(SpringApplication.exit(ctx, { code }))
    }

    private fun seed() {
        val dirName = props.library.seedDir.ifBlank { error("LIBRARY_SEED_DIR is not set") }
        val dir = Paths.get(dirName).toAbsolutePath().normalize()
        require(Files.isDirectory(dir)) { "LIBRARY_SEED_DIR is not a directory: $dir" }
        println("Library seed from $dir")
        val t = Tally()
        val oldS3 = oldS3Client()

        // 1. Starters (42): ids are the old UUIDs; the image name is the old file name.
        val starters = readJson(dir.resolve("starter-items.json")) as List<*>
        val missingStarterNames = mutableListOf<String>()
        for (raw in starters) {
            val s = raw as Map<*, *>
            val id = s["id"].str()
            val fileName = s["name"].str()
            val row = LibraryItem(
                id = id, source = LibraryService.STARTER, name = labelFromFileName(fileName), fileName = fileName,
                category = s["type"].str(), description = s["description"].str(), attributes = null,
                vibes = s["vibe"].strList(), styles = s["style"].strList(), imageKey = Keys.library(id),
            )
            upsert(row, t)
            val local = listOf(dir.resolve(fileName), dir.resolve("onboarding-clothing-items").resolve(fileName)).firstOrNull { Files.isRegularFile(it) }
            val ok = uploadImage(row.imageKey, t) {
                local?.let { Files.readAllBytes(it) } ?: oldS3?.let { fetchOld(it, fileName) }
            }
            if (!ok) { t.missing += "starter $id ($fileName)"; missingStarterNames += fileName }
        }

        // 2. Generated (540): id = slug; attributes = the ten KMP fields.
        val catalog = readJson(dir.resolve("catalog.json"))
        val items = (catalog as? Map<*, *>)?.get("items") as? List<*> ?: catalog as List<*>
        for (raw in items) {
            val c = raw as Map<*, *>
            val id = c["id"].str()
            val gender = c["gender"]?.toString()?.ifBlank { null }
            val row = LibraryItem(
                id = id, source = LibraryService.GENERATED, name = c["name"].str(), fileName = null,
                category = c["category"].str(), description = c["description"].str(),
                attributes = ATTRIBUTE_KEYS.associateWith { c[it] },
                brand = c["brand"]?.toString()?.ifBlank { null }, model = c["model"]?.toString()?.ifBlank { null },
                gender = gender, keywords = c["keywords"].strList(), vibes = emptyList(),
                styles = when (gender) { "men" -> listOf("masculine"); "women" -> listOf("feminine"); else -> emptyList() },
                imageKey = Keys.library(id),
            )
            upsert(row, t)
            val file = dir.resolve(c["image"]?.toString()?.ifBlank { null } ?: "cutout/$id.png").normalize()
            val ok = uploadImage(row.imageKey, t) { if (file.startsWith(dir) && Files.isRegularFile(file)) Files.readAllBytes(file) else null }
            if (!ok) t.missing += "generated $id (${dir.relativize(file)})"
        }

        println("Rows:   inserted=${t.inserted} updated=${t.updated} unchanged=${t.unchanged}")
        println("Images: uploaded=${t.uploaded} skipped(already there)=${t.skipped} missing=${t.missing.size}")
        if (t.missing.isNotEmpty()) {
            println("Missing images (rows were seeded anyway; re-run the seed after fetching them):")
            t.missing.forEach { println("  - $it") }
            if (missingStarterNames.isNotEmpty()) {
                println("Starter images live in the old private S3 bucket. Fetch them with:")
                println("  aws s3 cp s3://${oldBucket()}/$OLD_PREFIX/ ${dir.resolve(OLD_PREFIX)}/ --recursive --region ${props.old.s3Region}")
                println("or set OLD_S3_ACCESS_KEY and OLD_S3_SECRET and re-run the seed.")
            }
        }
    }

    /** Inserts a new row, rewrites a changed one, leaves an unchanged one alone. Keeps `active` and `created_at`. */
    private fun upsert(row: LibraryItem, t: Tally) {
        val existing = repo.findById(row.id).orElse(null)
        if (existing == null) {
            repo.save(row); t.inserted++; return
        }
        if (fingerprint(existing) == fingerprint(row)) { t.unchanged++; return }
        existing.apply {
            source = row.source; name = row.name; fileName = row.fileName; category = row.category
            description = row.description; attributes = row.attributes; brand = row.brand; model = row.model
            gender = row.gender; keywords = row.keywords; vibes = row.vibes; styles = row.styles
            imageKey = row.imageKey; updatedAt = Instant.now()
        }
        repo.save(existing); t.updated++
    }

    private fun fingerprint(i: LibraryItem) = listOf(
        i.source, i.name, i.fileName, i.category, i.description, i.attributes, i.brand, i.model, i.gender,
        i.keywords.toList(), i.vibes.toList(), i.styles.toList(), i.imageKey,
    )

    /** Returns false when the object is not stored and no source bytes were found. */
    private fun uploadImage(key: String, t: Tally, source: () -> ByteArray?): Boolean {
        if (storage.exists(Bucket.LIBRARY, key)) { t.skipped++; return true }
        val bytes = source() ?: return false
        storage.put(Bucket.LIBRARY, key, images.toWebp(bytes), "image/webp", "public, max-age=86400")
        t.uploaded++
        return true
    }

    private fun oldS3Client(): S3Client? {
        val o = props.old
        if (o.s3AccessKey.isBlank() || o.s3Secret.isBlank()) return null
        return S3Client.builder()
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(o.s3AccessKey, o.s3Secret)))
            .region(Region.of(o.s3Region)).build()
    }

    private fun fetchOld(s3: S3Client, fileName: String): ByteArray? = try {
        s3.getObjectAsBytes(GetObjectRequest.builder().bucket(oldBucket()).key("$OLD_PREFIX/$fileName").build()).asByteArray()
    } catch (_: NoSuchKeyException) {
        null
    }

    /** The old server's bucket (inventory D). Overridable with OLD_S3_BUCKET; not secret. */
    private fun oldBucket() = env.getProperty("OLD_S3_BUCKET", "whatshouldiwearr")

    private fun readJson(p: Path): Any {
        require(Files.isRegularFile(p)) { "Missing seed file: $p" }
        return mapper.readValue(p.toFile(), Any::class.java)
    }

    private fun Any?.str(): String = this?.toString() ?: ""
    private fun Any?.strList(): List<String> = (this as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

    companion object {
        const val OLD_PREFIX = "onboarding-clothing-items"

        /** The ten KMP attribute keys copied from catalog.json (brief 3.3, ruling 9.2). */
        val ATTRIBUTE_KEYS = listOf("name", "category", "description", "formality", "polish", "fitShape", "layeringRole", "colors", "season", "tags")

        /** Same rule as the app's HttpCatalog: "white_crewneck_tshirt.png" → "White crewneck tshirt". */
        fun labelFromFileName(fileName: String) =
            fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }
    }
}
