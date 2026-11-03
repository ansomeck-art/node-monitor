package cn.nodemon.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class HostStore {
    private static final String ALIAS = "cn.nodemon.app.host-token.v1";
    private final SharedPreferences prefs;
    HostStore(Context context) { prefs = context.getSharedPreferences("hosts", Context.MODE_PRIVATE); }
    JSONArray hosts() { try { return new JSONArray(prefs.getString("list", "[]")); } catch (Exception e) { return new JSONArray(); } }
    void save(JSONArray hosts) { prefs.edit().putString("list", hosts.toString()).apply(); }
    JSONObject widgetHost(JSONArray hosts) { String id=prefs.getString("widget_host", ""); for(int i=0;i<hosts.length();i++) { JSONObject host=hosts.optJSONObject(i); if(host!=null && id.equals(host.optString("id"))) return host; } return hosts.optJSONObject(0); }
    void setWidgetHost(String id) { prefs.edit().putString("widget_host", id).apply(); }
    boolean dark() { return prefs.getBoolean("dark", true); }
    void setDark(boolean value) { prefs.edit().putBoolean("dark", value).apply(); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) { KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"); gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); gen.generateKey(); }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    String protect(String plain) throws Exception { Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); byte[] bytes = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)); return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP); }
    String unprotect(String value) throws Exception { String[] parts = value.split(":", -1); if (parts.length != 2) throw new Exception("令牌无效"); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))); return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8); }
}
