package cn.nodemon.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class MonitorClient {
    static URI direct(String text, boolean allowLan) throws Exception {
        if (!text.contains("://")) text = "http://" + text;
        URI uri = new URI(text);
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")) || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getPort() > 65535) throw new Exception("请输入没有路径或参数的 HTTP(S) 地址");
        if (uri.getScheme().equals("http")) {
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost")) return uri;
            // Do not resolve hostnames: DNS rebinding could expose the token.
            if (!host.matches("[0-9.]+") && !host.matches("[0-9a-f:]+")) throw new Exception("HTTP 仅允许本机或可信局域网的 IP 地址");
            InetAddress ip = InetAddress.getByName(host);
            if (!ip.isLoopbackAddress() && !(allowLan && (ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || (ip.getAddress().length == 16 && (ip.getAddress()[0] & 0xfe) == 0xfc)))) throw new Exception("HTTP 仅限明确允许的可信局域网私有 IP");
        }
        return uri;
    }
    static void tailnet(String text) throws Exception {
        if (!text.contains("://")) text = "http://" + text;
        URI uri = new URI(text);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/")) || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getPort() > 65535) throw new Exception("请输入 Tailscale IP 或 .ts.net 地址");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".ts.net")) return;
        if (!host.matches("[0-9.]+") && !host.matches("[0-9a-f:]+")) throw new Exception("远程地址必须属于 Tailscale 网络");
        byte[] b = InetAddress.getByName(host).getAddress();
        if ((b.length == 4 && (b[0]&255) == 100 && (b[1]&255) >= 64 && (b[1]&255) <= 127) || (b.length == 16 && (b[0]&255)==0xfd && (b[1]&255)==0x7a && (b[2]&255)==0x11 && (b[3]&255)==0x5c && (b[4]&255)==0xa1 && (b[5]&255)==0xe0)) return;
        throw new Exception("远程地址必须属于 Tailscale 网络");
    }
    static JSONObject fetch(Context context, JSONObject host) throws Exception {
        String address = host.optString("address"), mode = host.optString("mode", "direct");
        String token = new HostStore(context).unprotect(host.getString("token"));
        JSONObject result;
        if (mode.equals("tailnet")) { tailnet(address); result = TailnetBridge.request(context, "fetch", address, token).getJSONObject("data"); }
        else {
            URI uri = direct(address, host.optBoolean("allowLan", false));
            URL endpoint = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/v1/snapshot", null, null).toURL();
            HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();
            try { conn.setConnectTimeout(8000); conn.setReadTimeout(8000); conn.setInstanceFollowRedirects(false); conn.setRequestProperty("Authorization", "Bearer " + token); conn.setRequestProperty("Accept", "application/json"); int status = conn.getResponseCode(); if (status != 200) throw new Exception("采集端返回 HTTP " + status);
                if (conn.getContentLengthLong() > 1048576) throw new Exception("数据过大");
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) { byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) { if (bytes.size()+n > 1048576) throw new Exception("数据过大"); bytes.write(buffer,0,n); } result = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)); }
            } finally { conn.disconnect(); }
        }
        if (result.optInt("schemaVersion") != 1) throw new Exception("采集端协议版本不兼容");
        return result;
    }
}
