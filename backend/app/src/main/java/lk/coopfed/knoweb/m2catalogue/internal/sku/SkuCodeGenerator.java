package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class SkuCodeGenerator {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder value = new StringBuilder("SKU-");
        for (int i = 0; i < 8; i++) {
            value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return value.toString();
    }
}
