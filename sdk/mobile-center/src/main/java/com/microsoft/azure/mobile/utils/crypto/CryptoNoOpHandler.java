package com.microsoft.azure.mobile.utils.crypto;

import android.content.Context;

import java.security.KeyStore;

/**
 * Handler that does not actually encrypt anything.
 */
class CryptoNoOpHandler implements CryptoHandler {

    @Override
    public String getAlgorithm() {
        return "None";
    }

    @Override
    public void generateKey(CryptoUtils.ICryptoFactory cryptoFactory, String alias, Context context) {
    }

    @Override
    public byte[] encrypt(CryptoUtils.ICryptoFactory cryptoFactory, int apiLevel, KeyStore.Entry keyStoreEntry, byte[] data) {
        return data;
    }

    @Override
    public byte[] decrypt(CryptoUtils.ICryptoFactory cryptoFactory, int apiLevel, KeyStore.Entry keyStoreEntry, byte[] data) {
        return data;
    }
}
