package com.diamond.auctions;

import io.minio.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
class Images {
    private static final String BUCKET = "vehicle-images";
    private final MinioClient minio;
    private final JdbcClient db;

    Images(JdbcClient db, @Value("${diamond.minio.url}") String endpoint,
           @Value("${diamond.minio.user}") String user, @Value("${diamond.minio.password}") String password) {
        this.db = db;
        this.minio = MinioClient.builder().endpoint(endpoint).credentials(user, password).build();
    }

    record Uploaded(String url) {}

    @PostMapping("/api/seller/images")
    @PreAuthorize("hasRole('SELLER')")
    @ResponseStatus(HttpStatus.CREATED)
    Uploaded upload(@AuthenticationPrincipal Jwt user, @RequestParam("file") MultipartFile upload) throws Exception {
        if (upload.isEmpty() || upload.getSize() > 8 * 1024 * 1024) throw invalid();
        byte[] sanitized;
        try (var input = ImageIO.createImageInputStream(upload.getInputStream())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!Set.of("JPEG", "png").contains(reader.getFormatName()) ||
                    (long) reader.getWidth(0) * reader.getHeight(0) > 16_000_000) throw invalid();
                var image = reader.read(0);
                var rgb = new java.awt.image.BufferedImage(image.getWidth(), image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = rgb.createGraphics();
                graphics.drawImage(image, 0, 0, java.awt.Color.WHITE, null);
                graphics.dispose();
                var output = new ByteArrayOutputStream();
                ImageIO.write(rgb, "jpeg", output);
                sanitized = output.toByteArray();
            } finally { reader.dispose(); }
        } catch (IOException error) { throw invalid(); }
        UUID id = UUID.randomUUID();
        try {
            if (!minio.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
                try { minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build()); }
                catch (io.minio.errors.ErrorResponseException error) {
                    if (!"BucketAlreadyOwnedByYou".equals(error.errorResponse().code())) throw error;
                }
            }
            minio.putObject(PutObjectArgs.builder().bucket(BUCKET).object(id.toString())
                .stream(new ByteArrayInputStream(sanitized), sanitized.length, -1).contentType("image/jpeg").build());
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is unavailable.");
        }
        db.sql("insert into image_asset(id,owner_id,content_type) values (?,?,'image/jpeg')").params(id, Listings.id(user)).update();
        return new Uploaded("/api/images/" + id);
    }

    @GetMapping("/api/images/{id}")
    void view(@PathVariable UUID id, @AuthenticationPrincipal Jwt user, HttpServletResponse response) throws Exception {
        var asset = db.sql("select owner_id from image_asset where id=?").param(id).query(UUID.class).optional()
            .orElseThrow(AuctionStore::missing);
        boolean publiclyListed = db.sql("""
            select exists(select 1 from auction where status not in ('DRAFT','REJECTED','REVIEW')
            and jsonb_exists(vehicle->'images',?))
            """).param("/api/images/" + id).query(Boolean.class).single();
        if (!publiclyListed && (user == null || (!asset.equals(Listings.id(user)) && !"ADMIN".equals(user.getClaimAsString("role")))))
            throw AuctionStore.missing();
        response.setHeader("Cache-Control", publiclyListed ? "public, max-age=3600" : "private, no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType("image/jpeg");
        try (var data = minio.getObject(GetObjectArgs.builder().bucket(BUCKET).object(id.toString()).build())) {
            data.transferTo(response.getOutputStream());
        }
    }

    void checkOwner(String url, UUID actor) {
        if (!url.matches("/api/images/[0-9a-f-]{36}")) throw invalid();
        try {
            UUID image = UUID.fromString(url.substring("/api/images/".length()));
            boolean owned = db.sql("select exists(select 1 from image_asset where id=? and owner_id=?)")
                .params(image, actor).query(Boolean.class).single();
            if (!owned) throw invalid();
        } catch (IllegalArgumentException error) { throw invalid(); }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an owned JPEG or PNG under 8 MB and 16 megapixels.");
    }
}
