package com.icbc.lineage.common;

import org.junit.Assert;
import org.junit.Test;

public class testDigestUtils {

    @Test
    public void testSha256IsStable() {
        Assert.assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                DigestUtils.sha256("abc"));
    }

    @Test
    public void utilityConstructorRemainsNonPublic() throws Exception {
        java.lang.reflect.Constructor<DigestUtils> constructor = DigestUtils.class.getDeclaredConstructor();
        Assert.assertFalse(constructor.canAccess(null));
        constructor.setAccessible(true);
        Assert.assertNotNull(constructor.newInstance());
    }

    @Test
    public void reportsMissingJdkDigestProvider() {
        java.security.Provider[] candidates = java.security.Security.getProviders("MessageDigest.SHA-256");
        java.util.List<java.util.Map.Entry<java.security.Provider, Integer>> removed = new java.util.ArrayList<>();
        java.security.Provider[] all = java.security.Security.getProviders();
        try {
            for (java.security.Provider provider : candidates) {
                int position = java.util.Arrays.asList(all).indexOf(provider) + 1;
                removed.add(java.util.Map.entry(provider, position));
                java.security.Security.removeProvider(provider.getName());
            }
            Assert.assertThrows(IllegalStateException.class, () -> DigestUtils.sha256("abc"));
        } finally {
            removed.stream().sorted(java.util.Map.Entry.comparingByValue())
                    .forEach(entry -> java.security.Security.insertProviderAt(entry.getKey(), entry.getValue()));
        }
    }
}
