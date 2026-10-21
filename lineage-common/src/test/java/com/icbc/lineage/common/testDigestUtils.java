package com.icbc.lineage.common;

import org.junit.Assert;
import org.junit.Test;

public class testDigestUtils {

    @Test
    public void testSha256IsStable() {
        Assert.assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                DigestUtils.sha256("abc"));
    }
}
