package com.marketshop.interfaces.storage;

import com.marketshop.application.proof.PrivateObjectDeliveryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/storage/private")
@ConditionalOnProperty(prefix = "market-shop.object-storage", name = "provider", havingValue = "local")
public class LocalPrivateObjectController {

    private final PrivateObjectDeliveryPort delivery;

    public LocalPrivateObjectController(PrivateObjectDeliveryPort delivery) {
        this.delivery = delivery;
    }

    @GetMapping("/{token}")
    public ResponseEntity<byte[]> content(@PathVariable String token) {
        PrivateObjectDeliveryPort.PrivateContent content = delivery.readSigned(token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }
}
