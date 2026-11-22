package com.UrNoTsuki.PIOXII;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class SupabaseClient {
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private final String baseUrl;
    private final String publicKey;
    private JSONObject session;

    SupabaseClient(Context context) throws IOException {
        baseUrl = BuildConfig.SUPABASE_URL.replaceAll("/+$", "");
        publicKey = BuildConfig.SUPABASE_ANON_KEY;
        if (baseUrl.isEmpty() || publicKey.isEmpty()) {
            throw new IOException("Falta la configuración de Supabase.");
        }
        context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    boolean isSignedIn() {
        return session != null && !session.optString("access_token").isEmpty();
    }

    String getUserId() {
        JSONObject user = session == null ? null : session.optJSONObject("user");
        return user == null ? "" : user.optString("id", "");
    }

    String getEmail() {
        JSONObject user = session == null ? null : session.optJSONObject("user");
        return user == null ? "" : user.optString("email", "");
    }

    void signIn(String email, String password) throws IOException {
        saveSession(requestAuth(
                "token?grant_type=password",
                json("email", email.trim(), "password", password)));
    }

    boolean signUp(String email, String password) throws IOException {
        JSONObject response = requestAuth(
                "signup", json("email", email.trim(), "password", password));
        if (response.optString("access_token").isEmpty()) {
            return false;
        }
        saveSession(response);
        return true;
    }

    void signInAnonymously() throws IOException {
        saveSession(requestAuth("signup", json("data", new JSONObject())));
    }

    void signOut() throws IOException {
        IOException failure = null;
        if (isSignedIn()) {
            try {
                request("POST", "auth/v1/logout", null, true, false);
            } catch (IOException error) {
                failure = error;
            }
        }
        session = null;
        if (failure != null) {
            throw failure;
        }
    }

    JSONArray getInventory() throws IOException {
        return getRows("balones",
                "select=id,codigo,disponible,prestado_a,fecha_prestamo&order=codigo.asc");
    }

    JSONArray getCalendarEvents() throws IOException {
        return getRows("teacher_calendar_events",
                "select=id,owner_id,teacher_name,event_title,event_date,start_time,end_time"
                        + "&order=event_date.asc,start_time.asc");
    }

    JSONArray getMatches() throws IOException {
        return getRows("tarde_deportiva_matches",
                "select=id,match_date,sport,start_time,team_a,team_b,score_a,score_b,notes"
                        + "&order=match_date.desc,start_time.asc");
    }

    JSONArray getRows(String table, String query) throws IOException {
        return asArray(request(
                "GET", "rest/v1/" + table + "?" + query, null, true, true));
    }

    JSONArray insert(String table, JSONObject value) throws IOException {
        return asArray(request(
                "POST", "rest/v1/" + table, value, true, true));
    }

    JSONArray insert(String table, JSONArray values) throws IOException {
        return asArray(request(
                "POST", "rest/v1/" + table, values, true, true));
    }

    JSONArray update(String table, String query, JSONObject value) throws IOException {
        return asArray(request(
                "PATCH", "rest/v1/" + table + "?" + query, value, true, true));
    }

    JSONArray delete(String table, String query) throws IOException {
        return asArray(request(
                "DELETE", "rest/v1/" + table + "?" + query, null, true, true));
    }

    private JSONArray asArray(String response) throws IOException {
        try {
            return response == null || response.isEmpty()
                    ? new JSONArray()
                    : new JSONArray(response);
        } catch (JSONException error) {
            throw new IOException("Supabase devolvió una respuesta inesperada.", error);
        }
    }

    private JSONObject requestAuth(String path, JSONObject body) throws IOException {
        String response = request(
                "POST", "auth/v1/" + path, body, false, false);
        try {
            return new JSONObject(response);
        } catch (JSONException error) {
            throw new IOException("Supabase devolvió una respuesta de acceso inesperada.",
                    error);
        }
    }

    private String request(
            String method,
            String path,
            Object body,
            boolean authenticated,
            boolean retryUnauthorized) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + "/" + path)
                    .openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("apikey", publicKey);
            connection.setRequestProperty("Accept", "application/json");
            if (path.startsWith("rest/v1/")
                    && (method.equals("POST")
                    || method.equals("PATCH")
                    || method.equals("DELETE"))) {
                connection.setRequestProperty("Prefer", "return=representation");
            }
            if (authenticated) {
                if (!isSignedIn()) {
                    throw new IOException("Inicia sesión para continuar.");
                }
                connection.setRequestProperty(
                        "Authorization",
                        "Bearer " + session.optString("access_token"));
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED
                    && authenticated
                    && retryUnauthorized) {
                refreshSession();
                return request(method, path, body, true, false);
            }
            if (status < 200 || status >= 300) {
                throw new IOException(errorMessage(response, status));
            }
            return response;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void refreshSession() throws IOException {
        if (session == null || session.optString("refresh_token").isEmpty()) {
            session = null;
            throw new IOException("La sesión venció. Inicia sesión de nuevo.");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("refresh_token", session.getString("refresh_token"));
        } catch (JSONException error) {
            throw new IOException("La sesión guardada no es válida.", error);
        }
        try {
            saveSession(requestAuth("token?grant_type=refresh_token", body));
        } catch (IOException error) {
            session = null;
            throw new IOException("La sesión venció. Inicia sesión de nuevo.", error);
        }
    }

    private void saveSession(JSONObject value) throws IOException {
        if (value.optString("access_token").isEmpty()
                || value.optString("refresh_token").isEmpty()
                || value.optJSONObject("user") == null) {
            throw new IOException("Supabase no devolvió una sesión válida.");
        }
        session = value;
    }

    private JSONObject json(Object... pairs) {
        JSONObject value = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) {
                value.put(String.valueOf(pairs[i]), pairs[i + 1]);
            }
            return value;
        } catch (JSONException error) {
            throw new IllegalStateException("No se pudo preparar la operación.", error);
        }
    }

    private String readResponse(HttpURLConnection connection, int status)
            throws IOException {
        InputStream input = status >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (input == null) {
            return "";
        }
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    private String errorMessage(String response, int status) {
        try {
            JSONObject error = new JSONObject(response);
            String message = error.optString("msg");
            if (message.isEmpty()) {
                message = error.optString("message");
            }
            if (message.isEmpty()) {
                message = error.optString("error_description");
            }
            if (!message.isEmpty()) {
                return message;
            }
        } catch (JSONException ignored) {
            // Use a generic status message for non-JSON upstream responses.
        }
        return "Supabase respondió con un error (" + status + ").";
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException error) {
            throw new IllegalStateException("No se pudo codificar el filtro.", error);
        }
    }
}
