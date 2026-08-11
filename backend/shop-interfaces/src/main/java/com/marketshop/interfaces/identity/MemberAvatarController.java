package com.marketshop.interfaces.identity;

import com.marketshop.application.identity.MemberProfileUseCase;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/member-avatars")
public class MemberAvatarController {

    private final MemberProfileUseCase profiles;

    public MemberAvatarController(MemberProfileUseCase profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/{userId}")
    public ResponseEntity<byte[]> avatar(@PathVariable long userId) {
        MemberProfileUseCase.AvatarContent content = profiles.avatar(userId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .cacheControl(CacheControl.noStore())
                .body(content.bytes());
    }
}
