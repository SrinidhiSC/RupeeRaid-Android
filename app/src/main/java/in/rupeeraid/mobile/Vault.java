package in.rupeeraid.mobile;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Private atomic files encrypted with a non-exportable Android Keystore key. */
final class Vault implements MobileApi.Storage {
    private final Context context;
    private static final String ALIAS = "RupeeRaid.mobile.v1";
    private final PricingRegion region;
    Vault(Context context) {
        this.context = context;
        region = PricingRegion.load(context);
    }
    private String regionalName(String name) {
        switch (name) {
            case "lists": case "snapshot": case "quotes": case "waitPrices": case "collectionPrices":
            case "cacheGeneration": case "priceAlerts": case "searchCache": return region.prefix() + name;
            default: return name.startsWith("guide-") ? region.prefix() + name : name;
        }
    }
    private AtomicFile file(String name) { return new AtomicFile(new File(context.getNoBackupFilesDir(), regionalName(name))); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return gen.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS, null);
    }
    public String read(String name) {
        try {
            byte[] data = file(name).readFully();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(data, 12)));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception unavailable) { return ""; }
    }
    public void write(String name, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        AtomicFile target = file(name);
        FileOutputStream stream = target.startWrite();
        try {
            stream.write(cipher.getIV());
            stream.write(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
            target.finishWrite(stream);
        } catch (Exception ex) { target.failWrite(stream); throw ex; }
    }
    public void remove(String name) { file(name).delete(); }
    void clearRegionalData(){
        File[] files=context.getNoBackupFilesDir().listFiles((dir,name)->name.startsWith("region-") || name.startsWith("guide-") || Arrays.asList("lists","snapshot","quotes","waitPrices","collectionPrices","priceAlerts","searchCache","cacheGeneration").contains(name));
        if(files!=null)for(File item:files)new AtomicFile(item).delete();
    }
}
