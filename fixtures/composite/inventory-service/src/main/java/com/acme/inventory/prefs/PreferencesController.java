package com.acme.inventory.prefs;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Base64;

@RestController
public class PreferencesController {

    @PostMapping("/api/prefs/apply")
    public String apply(@RequestBody String token) throws IOException, ClassNotFoundException {
        byte[] data = Base64.getDecoder().decode(token.trim());
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            Object prefs = in.readObject();
            return String.valueOf(prefs);
        }
    }
}
