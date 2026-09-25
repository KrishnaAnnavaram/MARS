package com.example.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

public final class CryptoUtil {

    public byte[] encrypt(SecretKey key, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(data);
    }
}
