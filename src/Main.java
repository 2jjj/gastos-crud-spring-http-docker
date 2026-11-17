import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Main {

    // Persistência em JSON simples
    private static final class Store {
        private final File file;
        private final List<Gasto> gastos = new ArrayList<>();
        private long nextId = 1;

        Store(String path) {
            this.file = new File(path);
        }

        synchronized void load() throws IOException {
            gastos.clear();
            nextId = 1;
            if (!file.exists()) return;
            String txt = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
            if (txt.isEmpty()) return;
            Object parsed = Json.parse(txt);
            if (!(parsed instanceof List<?> list)) return;
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Gasto g = Gasto.fromMap(m);
                    if (g != null) {
                        gastos.add(g);
                        nextId = Math.max(nextId, g.id + 1);
                    }
                }
            }
        }

        synchronized void save() throws IOException {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            String json = Json.stringify(gastosToList());
            try (BufferedWriter w = new BufferedWriter(new FileWriter(file, false))) {
                w.write(json);
            }
        }

        private List<Map<String, Object>> gastosToList() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Gasto g : gastos) out.add(g.toMap());
            return out;
        }

        synchronized List<Gasto> list(LocalDate inicio, LocalDate fim) {
            List<Gasto> out = new ArrayList<>();
            for (Gasto g : gastos) {
                if (inicio != null && g.data.isBefore(inicio)) continue;
                if (fim != null && g.data.isAfter(fim)) continue;
                out.add(g);
            }
            return out;
        }

        synchronized Gasto get(long id) {
            for (Gasto g : gastos) if (g.id == id) return g;
            return null;
        }

        synchronized Gasto create(GastoCreate req) {
            Gasto g = new Gasto();
            g.id = nextId++;
            g.descricao = req.descricao;
            g.valor = req.valor;
            g.data = req.data;
            gastos.add(g);
            return g;
        }

        synchronized Gasto update(long id, GastoUpdate req) {
            for (int i = 0; i < gastos.size(); i++) {
                if (gastos.get(i).id == id) {
                    Gasto g = gastos.get(i);
                    g.descricao = req.descricao;
                    g.valor = req.valor;
                    g.data = req.data;
                    return g;
                }
            }
            return null;
        }

        synchronized boolean delete(long id) {
            for (int i = 0; i < gastos.size(); i++) {
                if (gastos.get(i).id == id) {
                    gastos.remove(i);
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Gasto {
        long id;
        String descricao;
        double valor;
        LocalDate data;

        Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("id", id);
            m.put("descricao", descricao);
            m.put("valor", valor);
            m.put("data", data.toString());
            return m;
        }

        static Gasto fromMap(Map<?, ?> m) {
            try {
                Object idO = m.get("id");
                Object descO = m.get("descricao");
                Object valorO = m.get("valor");
                Object dataO = m.get("data");
                if (idO == null || descO == null || valorO == null || dataO == null) return null;
                long id = ((Number) idO).longValue();
                String descricao = String.valueOf(descO);
                double valor = ((Number) valorO).doubleValue();
                LocalDate data = LocalDate.parse(String.valueOf(dataO));
                if (descricao.isBlank()) return null;
                if (valor <= 0) return null;
                Gasto g = new Gasto();
                g.id = id;
                g.descricao = descricao;
                g.valor = valor;
                g.data = data;
                return g;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static final class GastoCreate {
        String descricao;
        double valor;
        LocalDate data;
    }

    private static final class GastoUpdate {
        String descricao;
        double valor;
        LocalDate data;
    }

    private static GastoCreate parseCreate(String body) {
        Object parsed = Json.parse(body);
        if (!(parsed instanceof Map<?, ?> m)) throw badRequest("JSON inválido");
        GastoCreate req = new GastoCreate();
        req.descricao = getString(m, "descricao");
        req.valor = getDouble(m, "valor");
        req.data = getDate(m, "data");
        validateCreate(req);
        return req;
    }

    private static GastoUpdate parseUpdate(String body) {
        Object parsed = Json.parse(body);
        if (!(parsed instanceof Map<?, ?> m)) throw badRequest("JSON inválido");
        GastoUpdate req = new GastoUpdate();
        req.descricao = getString(m, "descricao");
        req.valor = getDouble(m, "valor");
        req.data = getDate(m, "data");
        validateUpdate(req);
        return req;
    }

    private static void validateCreate(GastoCreate req) {
        if (req.descricao == null || req.descricao.isBlank()) throw badRequest("descricao é obrigatória");
        if (req.valor <= 0) throw badRequest("valor deve ser maior que 0");
        if (req.data == null) throw badRequest("data é obrigatória e deve estar em yyyy-MM-dd");
    }

    private static void validateUpdate(GastoUpdate req) {
        if (req.descricao == null || req.descricao.isBlank()) throw badRequest("descricao é obrigatória");
        if (req.valor <= 0) throw badRequest("valor deve ser maior que 0");
        if (req.data == null) throw badRequest("data é obrigatória e deve estar em yyyy-MM-dd");
    }

    private static String getString(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        return String.valueOf(v);
    }

    private static double getDouble(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw badRequest(key + " deve ser numérico");
        }
    }

    private static LocalDate getDate(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        try {
            return LocalDate.parse(String.valueOf(v));
        } catch (DateTimeParseException e) {
            throw badRequest(key + " deve estar em yyyy-MM-dd");
        }
    }

    private static RuntimeException badRequest(String msg) {
        return new RuntimeException(msg);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ex.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString().trim();
        }
    }

    private static void sendJson(HttpExchange ex, int code, Object obj) throws IOException {
        byte[] out = Json.stringify(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static void sendText(HttpExchange ex, int code, String msg) throws IOException {
        byte[] out = msg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", message);
        return m;
    }

    private static final class ApiHandler implements HttpHandler {
        private final Store store;

        ApiHandler(Store store) {
            this.store = store;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            URI uri = ex.getRequestURI();
            String path = uri.getPath();
            try {
                if (path.equals("/api/gastos") || path.equals("/api/gastos/")) {
                    if (method.equals("GET")) {
                        LocalDate inicio = parseQueryDate(uri.getQuery(), "inicio");
                        LocalDate fim = parseQueryDate(uri.getQuery(), "fim");
                        List<Gasto> out = store.list(inicio, fim);
                        List<Map<String, Object>> arr = new ArrayList<>();
                        for (Gasto g : out) arr.add(g.toMap());
                        sendJson(ex, 200, arr);
                        return;
                    }
                    if (method.equals("POST")) {
                        String body = readBody(ex);
                        GastoCreate req = parseCreate(body);
                        Gasto created;
                        synchronized (store) {
                            created = store.create(req);
                            store.save();
                        }
                        sendJson(ex, 201, created.toMap());
                        return;
                    }
                    sendText(ex, 405, "Método não permitido");
                    return;
                }

                if (path.startsWith("/api/gastos/")) {
                    String idPart = path.substring("/api/gastos/".length());
                    if (idPart.isEmpty()) {
                        sendText(ex, 404, "Não encontrado");
                        return;
                    }
                    long id;
                    try {
                        id = Long.parseLong(idPart);
                    } catch (NumberFormatException e) {
                        sendJson(ex, 400, err("id inválido"));
                        return;
                    }

                    if (method.equals("GET")) {
                        Gasto g = store.get(id);
                        if (g == null) {
                            sendJson(ex, 404, err("Gasto não encontrado"));
                            return;
                        }
                        sendJson(ex, 200, g.toMap());
                        return;
                    }

                    if (method.equals("PUT")) {
                        String body = readBody(ex);
                        GastoUpdate req = parseUpdate(body);
                        Gasto updated;
                        synchronized (store) {
                            updated = store.update(id, req);
                            if (updated == null) {
                                sendJson(ex, 404, err("Gasto não encontrado"));
                                return;
                            }
                            store.save();
                        }
                        sendJson(ex, 200, updated.toMap());
                        return;
                    }

                    if (method.equals("DELETE")) {
                        boolean ok;
                        synchronized (store) {
                            ok = store.delete(id);
                            if (ok) store.save();
                        }
                        if (!ok) {
                            sendJson(ex, 404, err("Gasto não encontrado"));
                            return;
                        }
                        sendJson(ex, 204, new HashMap<>());
                        return;
                    }

                    sendText(ex, 405, "Método não permitido");
                    return;
                }

                sendText(ex, 404, "Não encontrado");
            } catch (RuntimeException e) {
                String msg = e.getMessage();
                if (msg == null) msg = "Requisição inválida";
                sendJson(ex, 400, err(msg));
            } catch (Exception e) {
                sendJson(ex, 500, err("Erro interno"));
            }
        }

        private LocalDate parseQueryDate(String query, String key) {
            if (query == null || query.isBlank()) return null;
            for (String part : query.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) continue;
                if (!kv[0].equals(key)) continue;
                try {
                    return LocalDate.parse(kv[1]);
                } catch (DateTimeParseException e) {
                    throw badRequest(key + " deve estar em yyyy-MM-dd");
                }
            }
            return null;
        }
    }

    // JSON mínimo: arrays de objetos e objetos com tipos string/number/boolean/null
    private static final class Json {
        static Object parse(String s) {
            if (s == null) throw badRequest("JSON vazio");
            Parser p = new Parser(s);
            Object v = p.parseValue();
            p.skipWs();
            if (!p.eof()) throw badRequest("JSON inválido");
            return v;
        }

        static String stringify(Object v) {
            return new Stringifier().stringify(v);
        }

        private static final class Parser {
            private final String s;
            private int i;

            Parser(String s) {
                this.s = s;
            }

            boolean eof() {
                return i >= s.length();
            }

            void skipWs() {
                while (!eof()) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                    else break;
                }
            }

            Object parseValue() {
                skipWs();
                if (eof()) throw badRequest("JSON inválido");
                char c = s.charAt(i);
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == 't' || c == 'f') return parseBoolean();
                if (c == 'n') return parseNull();
                return parseNumber();
            }

            Map<String, Object> parseObject() {
                expect('{');
                skipWs();
                Map<String, Object> m = new HashMap<>();
                if (peek('}')) {
                    i++;
                    return m;
                }
                while (true) {
                    skipWs();
                    String key = parseString();
                    skipWs();
                    expect(':');
                    Object val = parseValue();
                    m.put(key, val);
                    skipWs();
                    if (peek(',')) {
                        i++;
                        continue;
                    }
                    if (peek('}')) {
                        i++;
                        return m;
                    }
                    throw badRequest("JSON inválido");
                }
            }

            List<Object> parseArray() {
                expect('[');
                skipWs();
                List<Object> out = new ArrayList<>();
                if (peek(']')) {
                    i++;
                    return out;
                }
                while (true) {
                    Object v = parseValue();
                    out.add(v);
                    skipWs();
                    if (peek(',')) {
                        i++;
                        continue;
                    }
                    if (peek(']')) {
                        i++;
                        return out;
                    }
                    throw badRequest("JSON inválido");
                }
            }

            String parseString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (!eof()) {
                    char c = s.charAt(i++);
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        if (eof()) break;
                        char e = s.charAt(i++);
                        if (e == '"' || e == '\\' || e == '/') sb.append(e);
                        else if (e == 'b') sb.append('\b');
                        else if (e == 'f') sb.append('\f');
                        else if (e == 'n') sb.append('\n');
                        else if (e == 'r') sb.append('\r');
                        else if (e == 't') sb.append('\t');
                        else throw badRequest("JSON inválido");
                    } else {
                        sb.append(c);
                    }
                }
                throw badRequest("JSON inválido");
            }

            Object parseNull() {
                if (s.startsWith("null", i)) {
                    i += 4;
                    return null;
                }
                throw badRequest("JSON inválido");
            }

            Boolean parseBoolean() {
                if (s.startsWith("true", i)) {
                    i += 4;
                    return true;
                }
                if (s.startsWith("false", i)) {
                    i += 5;
                    return false;
                }
                throw badRequest("JSON inválido");
            }

            Number parseNumber() {
                int start = i;
                if (peek('-')) i++;
                while (!eof() && Character.isDigit(s.charAt(i))) i++;
                boolean isFloat = false;
                if (!eof() && peek('.')) {
                    isFloat = true;
                    i++;
                    while (!eof() && Character.isDigit(s.charAt(i))) i++;
                }
                if (!eof() && (peek('e') || peek('E'))) {
                    isFloat = true;
                    i++;
                    if (!eof() && (peek('+') || peek('-'))) i++;
                    while (!eof() && Character.isDigit(s.charAt(i))) i++;
                }
                String num = s.substring(start, i);
                try {
                    if (isFloat) return Double.parseDouble(num);
                    return Long.parseLong(num);
                } catch (NumberFormatException e) {
                    throw badRequest("Número inválido");
                }
            }

            void expect(char c) {
                skipWs();
                if (eof() || s.charAt(i) != c) throw badRequest("JSON inválido");
                i++;
            }

            boolean peek(char c) {
                return !eof() && s.charAt(i) == c;
            }
        }

        private static final class Stringifier {
            String stringify(Object v) {
                if (v == null) return "null";
                if (v instanceof String s) return '"' + escape(s) + '"';
                if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
                if (v instanceof Map<?, ?> m) {
                    List<String> parts = new ArrayList<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() == null) continue;
                        String k = String.valueOf(e.getKey());
                        parts.add('"' + escape(k) + '"' + ':' + stringify(e.getValue()));
                    }
                    return '{' + String.join(",", parts) + '}';
                }
                if (v instanceof List<?> list) {
                    List<String> parts = new ArrayList<>();
                    for (Object o : list) parts.add(stringify(o));
                    return '[' + String.join(",", parts) + ']';
                }
                throw badRequest("Tipo não suportado no JSON");
            }

            private String escape(String s) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c == '"' || c == '\\') sb.append('\\').append(c);
                    else if (c == '\n') sb.append("\\n");
                    else if (c == '\r') sb.append("\\r");
                    else if (c == '\t') sb.append("\\t");
                    else sb.append(c);
                }
                return sb.toString();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        String dataPath = "data/gastos.json";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) port = Integer.parseInt(args[++i]);
            else if (args[i].equals("--data") && i + 1 < args.length) dataPath = args[++i];
        }

        Store store = new Store(dataPath);
        store.load();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/gastos", new ApiHandler(store));
        server.createContext("/api/gastos/", new ApiHandler(store));
        server.createContext("/", ex -> {
            if (ex.getRequestMethod().equals("GET")) {
                String msg = "API de Gastos. Use /api/gastos (GET, POST) e /api/gastos/{id} (GET, PUT, DELETE).";
                sendText(ex, 200, msg);
                return;
            }
            sendText(ex, 405, "Método não permitido");
        });

        server.setExecutor(null);
        server.start();
    }
}
