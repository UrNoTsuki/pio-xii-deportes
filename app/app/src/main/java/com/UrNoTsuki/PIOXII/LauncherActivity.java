package com.UrNoTsuki.PIOXII;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Build;
import android.view.WindowInsets;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LauncherActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(246, 247, 241);
    private static final int SURFACE = Color.WHITE;
    private static final int SURFACE_LIGHT = Color.rgb(237, 241, 232);
    private static final int BRAND_DARK = Color.rgb(25, 67, 45);
    private static final int ACCENT = Color.rgb(48, 113, 70);
    private static final int ACCENT_LIGHT = Color.rgb(223, 237, 220);
    private static final int TEXT = Color.rgb(31, 51, 39);
    private static final int MUTED = Color.rgb(105, 119, 108);
    private static final int DANGER = Color.rgb(167, 63, 54);
    private static final String[] SECTIONS = {
            "Inicio", "Balones", "Préstamos", "Agenda", "Deportes", "Administrar"
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SupabaseClient supabase;
    private JSONArray inventory = new JSONArray();
    private JSONArray calendarEvents = new JSONArray();
    private JSONArray matches = new JSONArray();
    private String section = "Inicio";
    private LinearLayout shell;
    private LinearLayout page;
    private ProgressBar busy;
    private byte[] pendingCsv;
    private Date calendarDate = new Date();
    private Date matchDate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(BRAND_DARK);
        window.setNavigationBarColor(BRAND_DARK);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        try {
            supabase = new SupabaseClient(this);
        } catch (IOException configurationError) {
            showFatal(configurationError.getMessage());
            return;
        }

        matchDate = nextFriday(new Date());
        if (supabase.isSignedIn()) {
            refreshData(true);
        } else {
            renderLogin(null);
        }
    }

    private void showFatal(String message) {
        LinearLayout root = vertical();
        root.setBackgroundColor(BACKGROUND);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        applySystemInsets(root, dp(24), dp(24), dp(24), dp(24));
        root.addView(label("No se pudo iniciar Pio XII Deportes", 22, TEXT, true));
        TextView detail = label(message == null ? "Revisa la configuración." : message,
                15, MUTED, false);
        detail.setGravity(Gravity.CENTER);
        root.addView(detail);
        setContentView(root);
    }

    private void renderLogin(String notice) {
        LinearLayout root = vertical();
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(24), dp(32), dp(24), dp(32));
        applySystemInsets(root, dp(24), dp(32), dp(24), dp(32));

        LinearLayout content = vertical();
        ImageView logo = logoImage();
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(92), dp(92));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        logoParams.bottomMargin = dp(18);
        content.addView(logo, logoParams);

        TextView eyebrow = label("PIO XII  ·  DEPORTES", 13, ACCENT, true);
        eyebrow.setGravity(Gravity.CENTER);
        content.addView(eyebrow);
        TextView welcome = label("Tu colegio,\nen movimiento.", 31, TEXT, true);
        welcome.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams welcomeParams = matchWrap();
        welcomeParams.topMargin = dp(9);
        content.addView(welcome, welcomeParams);
        TextView intro = label("Material deportivo, préstamos y actividades en un solo lugar.",
                15, MUTED, false);
        intro.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams introParams = matchWrap();
        introParams.topMargin = dp(9);
        introParams.bottomMargin = dp(24);
        content.addView(intro, introParams);

        LinearLayout card = vertical();
        card.setPadding(dp(20), dp(21), dp(20), dp(20));
        card.setBackground(surface(SURFACE, 22));
        card.setElevation(dp(5));
        card.addView(label("Bienvenido de nuevo", 20, TEXT, true));
        TextView subtitle = label("Accede con la misma cuenta del portal escolar.", 14, MUTED, false);
        LinearLayout.LayoutParams subtitleParams = wrap();
        subtitleParams.topMargin = dp(5);
        card.addView(subtitle, subtitleParams);

        if (notice != null) {
            TextView message = label(notice, 14, DANGER, false);
            LinearLayout.LayoutParams messageParams = wrap();
            messageParams.topMargin = dp(16);
            card.addView(message, messageParams);
        }

        EditText email = input("Correo electrónico", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setSingleLine(true);
        LinearLayout.LayoutParams inputParams = matchWrap();
        inputParams.topMargin = dp(24);
        card.addView(email, inputParams);

        EditText password = input("Contraseña",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSingleLine(true);
        inputParams = matchWrap();
        inputParams.topMargin = dp(12);
        card.addView(password, inputParams);

        Button signIn = action("Iniciar sesión", false);
        inputParams = matchWrap();
        inputParams.topMargin = dp(20);
        card.addView(signIn, inputParams);
        signIn.setOnClickListener(view -> {
            String userEmail = email.getText().toString().trim();
            String userPassword = password.getText().toString();
            if (userEmail.isEmpty() || userPassword.isEmpty()) {
                toast("Escribe tu correo y contraseña.");
                return;
            }
            setBusy(true);
            executor.execute(() -> {
                try {
                    supabase.signIn(userEmail, userPassword);
                    runOnUiThread(this::finishAuthentication);
                } catch (IOException error) {
                    runOnUiThread(() -> {
                        setBusy(false);
                        renderLogin(error.getMessage());
                    });
                }
            });
        });

        Button createAccount = action("Crear cuenta", true);
        inputParams = matchWrap();
        inputParams.topMargin = dp(8);
        card.addView(createAccount, inputParams);
        createAccount.setOnClickListener(view -> renderSignUp(null));

        TextView guestNote = label(
                "La cuenta de invitado tiene acceso para modificar los datos compartidos.",
                12, MUTED, false);
        LinearLayout.LayoutParams guestNoteParams = matchWrap();
        guestNoteParams.topMargin = dp(14);
        card.addView(guestNote, guestNoteParams);
        Button guest = action("Continuar como invitado", true);
        inputParams = matchWrap();
        inputParams.topMargin = dp(8);
        card.addView(guest, inputParams);
        guest.setOnClickListener(view -> signInAsGuest());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        content.addView(card, matchWrap());
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        content.setAlpha(0f);
        content.setTranslationY(dp(14));
        content.animate().alpha(1f).translationY(0).setDuration(360).start();
    }

    private void renderSignUp(String notice) {
        LinearLayout root = vertical();
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(24), dp(32), dp(24), dp(32));
        applySystemInsets(root, dp(24), dp(32), dp(24), dp(32));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = vertical();
        content.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = logoImage();
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(82), dp(82));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        logoParams.bottomMargin = dp(18);
        content.addView(logo, logoParams);

        TextView eyebrow = label("PIO XII  ·  DEPORTES", 13, ACCENT, true);
        eyebrow.setGravity(Gravity.CENTER);
        content.addView(eyebrow);
        TextView title = label("Crear cuenta", 28, TEXT, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(8);
        titleParams.bottomMargin = dp(20);
        content.addView(title, titleParams);

        LinearLayout card = vertical();
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(surface(SURFACE, 22));
        card.setElevation(dp(4));
        if (notice != null) {
            TextView message = label(notice, 14, ACCENT, false);
            LinearLayout.LayoutParams messageParams = matchWrap();
            messageParams.bottomMargin = dp(14);
            card.addView(message, messageParams);
        }

        EditText email = input("Correo electrónico", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        email.setSingleLine(true);
        card.addView(email, matchWrap());
        EditText password = input("Contraseña",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSingleLine(true);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        card.addView(password, params);
        EditText confirmation = input("Confirmar contraseña",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirmation.setSingleLine(true);
        params = matchWrap();
        params.topMargin = dp(10);
        card.addView(confirmation, params);

        Button create = action("Registrarme", false);
        params = matchWrap();
        params.topMargin = dp(16);
        card.addView(create, params);
        create.setOnClickListener(view -> {
            String accountEmail = email.getText().toString().trim();
            String accountPassword = password.getText().toString();
            if (accountEmail.isEmpty() || accountPassword.isEmpty()) {
                toast("Escribe tu correo y contraseña.");
            } else if (!accountPassword.equals(confirmation.getText().toString())) {
                toast("Las contraseñas no coinciden.");
            } else {
                createAccount(accountEmail, accountPassword);
            }
        });

        Button back = action("Volver a iniciar sesión", true);
        params = matchWrap();
        params.topMargin = dp(8);
        card.addView(back, params);
        back.setOnClickListener(view -> renderLogin(null));

        content.addView(card, matchWrap());
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        content.setAlpha(0f);
        content.setTranslationY(dp(10));
        content.animate().alpha(1f).translationY(0).setDuration(300).start();
    }

    private void createAccount(String email, String password) {
        setBusy(true);
        executor.execute(() -> {
            try {
                boolean signedIn = supabase.signUp(email, password);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (signedIn) {
                        finishAuthentication();
                    } else {
                        renderSignUp(
                                "Cuenta creada. Revisa tu correo para confirmar la cuenta "
                                        + "y luego inicia sesión.");
                    }
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    toast(error.getMessage());
                });
            }
        });
    }

    private void signInAsGuest() {
        new AlertDialog.Builder(this)
                .setTitle("Entrar como invitado")
                .setMessage(
                        "El invitado puede modificar el inventario, los préstamos, "
                                + "el calendario y los resultados compartidos.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Continuar", (dialog, which) -> {
                    setBusy(true);
                    executor.execute(() -> {
                        try {
                            supabase.signInAnonymously();
                            runOnUiThread(this::finishAuthentication);
                        } catch (IOException error) {
                            runOnUiThread(() -> {
                                setBusy(false);
                                toast(error.getMessage());
                            });
                        }
                    });
                })
                .show();
    }

    private void finishAuthentication() {
        setBusy(false);
        section = "Inicio";
        renderShell();
        refreshData(true);
    }

    private void renderShell() {
        shell = vertical();
        shell.setBackgroundColor(BACKGROUND);
        applySystemInsets(shell, 0, 0, 0, 0);

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(12), dp(12));
        header.setBackground(gradient(BRAND_DARK, ACCENT, 0));
        ImageView logo = logoImage();
        header.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout brand = vertical();
        brand.setPadding(dp(10), 0, 0, 0);
        brand.addView(label("PIO XII", 16, Color.WHITE, true));
        TextView product = label("DEPORTES", 10, Color.rgb(208, 228, 205), true);
        product.setLetterSpacing(0.12f);
        brand.addView(product);
        header.addView(brand, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button sync = action("↻", true);
        sync.setTextSize(21);
        sync.setContentDescription("Actualizar datos");
        header.addView(sync);
        sync.setOnClickListener(view -> refreshData(true));
        Button logout = action("Salir", true);
        header.addView(logout);
        logout.setOnClickListener(view -> confirm(
                "Cerrar sesión",
                "Se cerrará tu sesión en este dispositivo.",
                "Cerrar sesión",
                () -> signOut()));
        shell.addView(header);

        FrameLayout body = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = vertical();
        page.setPadding(dp(18), dp(12), dp(18), dp(30));
        scroll.addView(page);
        body.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        busy = new ProgressBar(this);
        busy.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        FrameLayout.LayoutParams busyParams = new FrameLayout.LayoutParams(
                dp(42), dp(42), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        busyParams.topMargin = dp(8);
        busy.setVisibility(View.GONE);
        body.addView(busy, busyParams);
        shell.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout navigation = horizontal();
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(5), dp(8), dp(5), dp(8));
        navigation.setBackground(surface(SURFACE, 20));
        navigation.setElevation(dp(8));
        navigation.setClipToPadding(false);
        String[] labels = {"Inicio", "Balones", "Prést.", "Agenda", "Deportes", "Admin"};
        int[] icons = {
                R.drawable.nav_home,
                R.drawable.nav_inventory,
                R.drawable.nav_loans,
                R.drawable.nav_calendar,
                R.drawable.nav_sports,
                R.drawable.nav_admin
        };
        for (int i = 0; i < SECTIONS.length; i++) {
            String item = SECTIONS[i];
            LinearLayout navItem = navigationItem(labels[i], icons[i], item.equals(section));
            navigation.addView(navItem, new LinearLayout.LayoutParams(
                    0, dp(62), 1));
            navItem.setOnClickListener(view -> {
                if (!section.equals(item)) {
                    section = item;
                    renderShell();
                }
            });
        }
        LinearLayout.LayoutParams navigationParams = matchWrap();
        navigationParams.setMargins(dp(10), dp(6), dp(10), dp(8));
        shell.addView(navigation, navigationParams);
        setContentView(shell);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            shell.requestApplyInsets();
        }

        switch (section) {
            case "Balones":
                renderInventory();
                break;
            case "Préstamos":
                renderLoans();
                break;
            case "Agenda":
                renderCalendar();
                break;
            case "Deportes":
                renderSports();
                break;
            case "Administrar":
                renderAdministration();
                break;
            default:
                renderDashboard();
        }
        animatePageEntrance();
    }

    private void renderDashboard() {
        page.addView(dashboardHero());
        int total = inventory.length();
        int available = countBalls(true);
        int loaned = total - available;

        LinearLayout metrics = horizontal();
        metrics.addView(metric("Total", String.valueOf(total)));
        metrics.addView(metric("Disponibles", String.valueOf(available)));
        metrics.addView(metric("Prestados", String.valueOf(loaned)));
        page.addView(metrics);

        page.addView(sectionTitle("Accesos rápidos"));
        LinearLayout shortcuts = horizontal();
        Button loanButton = action("＋  Préstamo", false);
        Button returnButton = action("↩  Devolución", true);
        LinearLayout.LayoutParams shortcutParams = new LinearLayout.LayoutParams(0,
                dp(52), 1);
        shortcutParams.setMargins(0, 0, dp(5), 0);
        shortcuts.addView(loanButton, shortcutParams);
        shortcutParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        shortcutParams.setMargins(dp(5), 0, 0, 0);
        shortcuts.addView(returnButton, shortcutParams);
        page.addView(shortcuts);
        loanButton.setOnClickListener(view -> {
            section = "Préstamos";
            renderShell();
            startLoan();
        });
        returnButton.setOnClickListener(view -> {
            section = "Préstamos";
            renderShell();
        });

        page.addView(sectionTitle("Préstamos pendientes"));
        boolean hasLoans = false;
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject ball = inventory.optJSONObject(i);
            if (ball != null && !ball.optBoolean("disponible", true)) {
                page.addView(ballCard(ball, false));
                hasLoans = true;
            }
        }
        if (!hasLoans) {
            page.addView(emptyState("No hay balones prestados."));
        }

        page.addView(sectionTitle("Próximos eventos"));
        int shown = 0;
        String today = formatIso(new Date());
        for (int i = 0; i < calendarEvents.length() && shown < 3; i++) {
            JSONObject event = calendarEvents.optJSONObject(i);
            if (event != null && event.optString("event_date").compareTo(today) >= 0) {
                page.addView(eventCard(event, false));
                shown++;
            }
        }
        if (shown == 0) {
            page.addView(emptyState("No hay eventos en el calendario."));
        }
        Button refresh = action("Actualizar datos", true);
        page.addView(refresh);
        refresh.setOnClickListener(view -> refreshData(true));
    }

    private View dashboardHero() {
        LinearLayout hero = horizontal();
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(14), dp(18));
        hero.setBackground(gradient(BRAND_DARK, ACCENT, 22));
        hero.setElevation(dp(4));

        LinearLayout copy = vertical();
        copy.addView(label("PIO XII  ·  ESPACIO DEPORTIVO", 11,
                Color.rgb(211, 231, 205), true));
        TextView title = label("Todo listo\npara jugar.", 25, Color.WHITE, true);
        LinearLayout.LayoutParams titleParams = wrap();
        titleParams.topMargin = dp(7);
        copy.addView(title, titleParams);
        TextView date = label(
                new SimpleDateFormat("EEEE, d MMMM", new Locale("es")).format(new Date()),
                13, Color.rgb(224, 237, 220), false);
        LinearLayout.LayoutParams dateParams = wrap();
        dateParams.topMargin = dp(7);
        copy.addView(date, dateParams);
        hero.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView logo = logoImage();
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        logoParams.setMargins(dp(8), 0, 0, 0);
        hero.addView(logo, logoParams);

        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(15);
        hero.setLayoutParams(params);
        return hero;
    }

    private void renderInventory() {
        page.addView(heading("Inventario", "Busca y filtra los balones del colegio."));
        EditText search = input("Buscar por código o estudiante", InputType.TYPE_CLASS_TEXT);
        page.addView(search, matchWrap());
        Spinner status = spinner(new String[]{"Todos", "Disponibles", "Prestados"});
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(10);
        page.addView(status, statusParams);

        Button export = action("Exportar CSV", true);
        LinearLayout.LayoutParams exportParams = matchWrap();
        exportParams.topMargin = dp(10);
        page.addView(export, exportParams);
        export.setOnClickListener(view -> exportInventory());

        LinearLayout rows = vertical();
        LinearLayout.LayoutParams rowsParams = matchWrap();
        rowsParams.topMargin = dp(12);
        page.addView(rows, rowsParams);
        Runnable update = () -> renderInventoryRows(
                rows, search.getText().toString(), status.getSelectedItemPosition());
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                update.run();
            }
            @Override
            public void afterTextChanged(Editable s) { }
        });
        status.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    android.widget.AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        update.run();
    }

    private void renderInventoryRows(LinearLayout rows, String query, int filter) {
        rows.removeAllViews();
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        int count = 0;
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject ball = inventory.optJSONObject(i);
            if (ball == null) {
                continue;
            }
            boolean available = ball.optBoolean("disponible", true);
            if ((filter == 1 && !available) || (filter == 2 && available)) {
                continue;
            }
            String code = ball.optString("codigo", "");
            String borrower = ball.optString("prestado_a", "");
            if (!normalized.isEmpty()
                    && !(code + " " + borrower).toLowerCase(Locale.ROOT).contains(normalized)) {
                continue;
            }
            rows.addView(ballCard(ball, false));
            count++;
        }
        if (count == 0) {
            rows.addView(emptyState("No hay balones que coincidan con la búsqueda."));
        }
    }

    private void renderLoans() {
        page.addView(heading("Préstamos y devoluciones",
                "Registra los movimientos del material deportivo."));
        Button newLoan = action("Nuevo préstamo", false);
        page.addView(newLoan, matchWrap());
        newLoan.setOnClickListener(view -> startLoan());

        page.addView(sectionTitle("En préstamo"));
        int loaned = 0;
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject ball = inventory.optJSONObject(i);
            if (ball != null && !ball.optBoolean("disponible", true)) {
                page.addView(ballCard(ball, true));
                loaned++;
            }
        }
        if (loaned == 0) {
            page.addView(emptyState("No hay balones pendientes de devolución."));
        }

        page.addView(sectionTitle("Disponibles"));
        int available = 0;
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject ball = inventory.optJSONObject(i);
            if (ball != null && ball.optBoolean("disponible", true)) {
                page.addView(ballCard(ball, false));
                available++;
            }
        }
        if (available == 0) {
            page.addView(emptyState("No hay balones disponibles."));
        }
    }

    private void startLoan() {
        List<JSONObject> available = ballsWithStatus(true);
        if (available.isEmpty()) {
            toast("No hay balones disponibles.");
            return;
        }
        String[] codes = new String[available.size()];
        for (int i = 0; i < available.size(); i++) {
            codes[i] = available.get(i).optString("codigo");
        }
        new AlertDialog.Builder(this)
                .setTitle("Selecciona un balón")
                .setItems(codes, (dialog, which) ->
                        promptBorrower(available.get(which).optString("codigo")))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void promptBorrower(String code) {
        EditText borrower = input("Nombre del estudiante", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        new AlertDialog.Builder(this)
                .setTitle("Préstamo · " + code)
                .setView(withDialogPadding(borrower))
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Confirmar", (dialog, which) -> {
                    String name = borrower.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("Escribe el nombre del estudiante.");
                        return;
                    }
                    JSONObject update = json(
                            "disponible", false,
                            "prestado_a", name,
                            "fecha_prestamo", isoTimestamp());
                    mutate("Préstamo registrado.", () -> {
                        JSONArray changed = supabase.update(
                                "balones",
                                "codigo=eq." + SupabaseClient.encode(code)
                                        + "&disponible=eq.true&select=codigo",
                                update);
                        requireChanged(changed, "El balón ya no está disponible.");
                        return changed;
                    });
                })
                .show();
    }

    private void returnBall(JSONObject ball) {
        String code = ball.optString("codigo");
        confirm("Registrar devolución",
                "¿Confirmas que " + code + " fue devuelto?",
                "Confirmar",
                () -> mutate("Devolución registrada.", () -> {
                    JSONArray changed = supabase.update(
                            "balones",
                            "codigo=eq." + SupabaseClient.encode(code)
                                    + "&disponible=eq.false&select=codigo",
                            json("disponible", true, "prestado_a", JSONObject.NULL,
                                    "fecha_prestamo", JSONObject.NULL));
                    requireChanged(changed, "No se pudo actualizar este balón.");
                    return changed;
                }));
    }

    private void renderAdministration() {
        page.addView(heading("Administrar balones",
                "Agrega códigos o modifica y retira balones disponibles."));

        page.addView(sectionTitle("Agregar varios"));
        EditText codes = input("Un código por línea", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        codes.setMinLines(3);
        codes.setGravity(Gravity.TOP | Gravity.START);
        page.addView(codes, matchWrap());
        Button add = action("Agregar al inventario", false);
        page.addView(add, matchWrap());
        add.setOnClickListener(view -> addBalls(codes.getText().toString()));

        page.addView(sectionTitle("Balones disponibles"));
        int count = 0;
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject ball = inventory.optJSONObject(i);
            if (ball != null && ball.optBoolean("disponible", true)) {
                page.addView(adminBallCard(ball));
                count++;
            }
        }
        if (count == 0) {
            page.addView(emptyState("No hay balones disponibles para administrar."));
        }
    }

    private void addBalls(String text) {
        Set<String> existing = new HashSet<>();
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject ball = inventory.optJSONObject(i);
            if (ball != null) {
                existing.add(ball.optString("codigo").trim().toLowerCase(Locale.ROOT));
            }
        }
        Set<String> seen = new HashSet<>();
        JSONArray newBalls = new JSONArray();
        for (String raw : text.split("\\R")) {
            String code = raw.trim();
            if (code.isEmpty()) {
                continue;
            }
            String normalized = code.toLowerCase(Locale.ROOT);
            if (existing.contains(normalized) || !seen.add(normalized)) {
                toast("Hay códigos repetidos. No se agregó ningún balón.");
                return;
            }
            newBalls.put(json("codigo", code, "disponible", true));
        }
        if (newBalls.length() == 0) {
            toast("Escribe al menos un código de balón.");
            return;
        }
        mutate("Balones agregados.", () -> {
            JSONArray inserted = supabase.insert("balones", newBalls);
            if (inserted.length() != newBalls.length()) {
                throw new IOException("No se confirmaron todos los balones agregados.");
            }
            return inserted;
        });
    }

    private View adminBallCard(JSONObject ball) {
        LinearLayout card = card();
        card.addView(label(ball.optString("codigo"), 17, TEXT, true));
        LinearLayout buttons = horizontal();
        Button edit = action("Cambiar código", true);
        Button remove = action("Retirar", true);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                0, dp(44), 1);
        buttonParams.topMargin = dp(10);
        buttonParams.setMargins(0, dp(10), dp(6), 0);
        buttons.addView(edit, buttonParams);
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(
                0, dp(44), 1);
        removeParams.topMargin = dp(10);
        removeParams.setMargins(dp(6), dp(10), 0, 0);
        buttons.addView(remove, removeParams);
        card.addView(buttons);

        String code = ball.optString("codigo");
        edit.setOnClickListener(view -> editBallCode(code));
        remove.setOnClickListener(view -> confirm(
                "Retirar balón",
                "Se eliminará permanentemente " + code + ".",
                "Eliminar",
                () -> mutate("Balón retirado.", () -> {
                    JSONArray deleted = supabase.delete(
                            "balones",
                            "codigo=eq." + SupabaseClient.encode(code)
                                    + "&disponible=eq.true&select=codigo");
                    requireChanged(deleted, "No se pudo retirar el balón.");
                    return deleted;
                })));
        return card;
    }

    private void editBallCode(String oldCode) {
        EditText newCode = input("Código nuevo", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        newCode.setText(oldCode);
        new AlertDialog.Builder(this)
                .setTitle("Cambiar código")
                .setView(withDialogPadding(newCode))
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String value = newCode.getText().toString().trim();
                    if (value.isEmpty() || value.equals(oldCode)) {
                        toast("Escribe un código distinto.");
                        return;
                    }
                    for (int i = 0; i < inventory.length(); i++) {
                        JSONObject ball = inventory.optJSONObject(i);
                        if (ball != null && ball.optString("codigo").equalsIgnoreCase(value)) {
                            toast("Ese código ya está registrado.");
                            return;
                        }
                    }
                    mutate("Código actualizado.", () -> {
                        JSONArray changed = supabase.update(
                                "balones",
                                "codigo=eq." + SupabaseClient.encode(oldCode)
                                        + "&disponible=eq.true&select=codigo",
                                json("codigo", value));
                        requireChanged(changed, "El balón ya no está disponible.");
                        return changed;
                    });
                })
                .show();
    }

    private void renderCalendar() {
        page.addView(heading("Calendario de profesores",
                "Todos pueden consultar los eventos; cada persona cancela los propios."));
        Button chooseDate = action("Fecha: " + formatDate(calendarDate), true);
        page.addView(chooseDate, matchWrap());
        chooseDate.setOnClickListener(view -> chooseDate(calendarDate,
                value -> {
                    calendarDate = value;
                    renderShell();
                }, false));

        Button create = action("Agregar un evento", false);
        LinearLayout.LayoutParams createParams = matchWrap();
        createParams.topMargin = dp(10);
        page.addView(create, createParams);
        create.setOnClickListener(view -> createCalendarEvent());

        page.addView(sectionTitle("Eventos del día"));
        String selectedDate = formatIso(calendarDate);
        int count = 0;
        for (int i = 0; i < calendarEvents.length(); i++) {
            JSONObject event = calendarEvents.optJSONObject(i);
            if (event != null && selectedDate.equals(event.optString("event_date"))) {
                page.addView(eventCard(event, true));
                count++;
            }
        }
        if (count == 0) {
            page.addView(emptyState("No hay eventos para esta fecha."));
        }

        page.addView(sectionTitle("Próximos eventos"));
        int upcoming = 0;
        for (int i = 0; i < calendarEvents.length() && upcoming < 20; i++) {
            JSONObject event = calendarEvents.optJSONObject(i);
            if (event != null && event.optString("event_date").compareTo(selectedDate) > 0) {
                page.addView(eventCard(event, true));
                upcoming++;
            }
        }
        if (upcoming == 0) {
            page.addView(emptyState("No hay eventos posteriores."));
        }
    }

    private void createCalendarEvent() {
        EditText teacher = input("Nombre del profesor", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText title = input("Clase, examen, reunión...", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        Date[] eventDate = {calendarDate};
        String[] startTime = {"08:00"};
        String[] endTime = {"08:45"};
        LinearLayout form = vertical();
        form.addView(teacher, matchWrap());
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        form.addView(title, params);
        Button dateButton = action("Fecha: " + formatDate(eventDate[0]), true);
        Button startButton = action("Inicio: " + startTime[0], true);
        Button endButton = action("Fin: " + endTime[0], true);
        params = matchWrap();
        params.topMargin = dp(12);
        form.addView(dateButton, params);
        form.addView(startButton, matchWrap());
        form.addView(endButton, matchWrap());
        dateButton.setOnClickListener(view -> chooseDate(eventDate[0], value -> {
            eventDate[0] = value;
            dateButton.setText("Fecha: " + formatDate(value));
        }, false));
        startButton.setOnClickListener(view -> chooseTime(startTime[0], value -> {
            startTime[0] = value;
            startButton.setText("Inicio: " + value);
        }));
        endButton.setOnClickListener(view -> chooseTime(endTime[0], value -> {
            endTime[0] = value;
            endButton.setText("Fin: " + value);
        }));

        new AlertDialog.Builder(this)
                .setTitle("Nuevo evento")
                .setView(withDialogPadding(form))
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String teacherName = teacher.getText().toString().trim();
                    String eventTitle = title.getText().toString().trim();
                    if (teacherName.isEmpty() || eventTitle.isEmpty()) {
                        toast("Completa el nombre del profesor y el evento.");
                        return;
                    }
                    if (endTime[0].compareTo(startTime[0]) <= 0) {
                        toast("La hora de finalización debe ser posterior al inicio.");
                        return;
                    }
                    mutate("Evento agregado al calendario.", () -> {
                        JSONArray added = supabase.insert("teacher_calendar_events",
                                json("teacher_name", teacherName,
                                        "event_title", eventTitle,
                                        "event_date", formatIso(eventDate[0]),
                                        "start_time", startTime[0] + ":00",
                                        "end_time", endTime[0] + ":00"));
                        requireChanged(added, "No se pudo guardar el evento.");
                        return added;
                    });
                })
                .show();
    }

    private View eventCard(JSONObject event, boolean allowCancel) {
        LinearLayout card = card();
        String start = event.optString("start_time", "");
        String end = event.optString("end_time", "");
        card.addView(label(event.optString("event_title"), 17, TEXT, true));
        card.addView(label(
                event.optString("event_date") + "  ·  "
                        + shortTime(start) + "–" + shortTime(end),
                14, ACCENT, false));
        card.addView(label("Profesor: " + event.optString("teacher_name"), 14, MUTED, false));
        if (allowCancel && supabase.getUserId().equals(event.optString("owner_id"))) {
            Button cancel = action("Cancelar mi evento", true);
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(8);
            card.addView(cancel, params);
            cancel.setOnClickListener(view -> confirm(
                    "Cancelar evento",
                    "¿Quieres cancelar este evento?",
                    "Cancelar evento",
                    () -> mutate("Evento cancelado.", () -> {
                        JSONArray deleted = supabase.delete(
                                "teacher_calendar_events",
                                "id=eq." + SupabaseClient.encode(event.optString("id"))
                                        + "&owner_id=eq."
                                        + SupabaseClient.encode(supabase.getUserId())
                                        + "&select=id");
                        requireChanged(deleted,
                                "Solo puedes cancelar tus propios eventos.");
                        return deleted;
                    })));
        }
        return card;
    }

    private void renderSports() {
        if (matchDate == null) {
            matchDate = nextFriday(new Date());
        }
        page.addView(heading("Tarde Deportiva",
                "Programa fixtures, registra resultados y consulta el historial."));
        Button chooseDate = action("Viernes: " + formatDate(matchDate), true);
        page.addView(chooseDate, matchWrap());
        chooseDate.setOnClickListener(view -> chooseDate(matchDate, value -> {
            if (dayOfWeek(value) != Calendar.FRIDAY) {
                toast("Tarde Deportiva solo admite partidos de los viernes.");
                return;
            }
            matchDate = value;
            renderShell();
        }, true));

        List<JSONObject> selectedMatches = matchesForDate(formatIso(matchDate));
        int played = 0;
        int draws = 0;
        Set<String> sports = new HashSet<>();
        TreeMap<String, int[]> standings = new TreeMap<>();
        for (JSONObject match : selectedMatches) {
            sports.add(match.optString("sport"));
            if (!match.isNull("score_a") && !match.isNull("score_b")) {
                played++;
                String teamA = match.optString("team_a");
                String teamB = match.optString("team_b");
                int scoreA = match.optInt("score_a");
                int scoreB = match.optInt("score_b");
                int[] statsA = standings.computeIfAbsent(teamA, key -> new int[6]);
                int[] statsB = standings.computeIfAbsent(teamB, key -> new int[6]);
                statsA[0]++;
                statsB[0]++;
                statsA[4] += scoreA;
                statsA[5] += scoreB;
                statsB[4] += scoreB;
                statsB[5] += scoreA;
                if (scoreA > scoreB) {
                    statsA[1]++;
                    statsB[3]++;
                } else if (scoreB > scoreA) {
                    statsB[1]++;
                    statsA[3]++;
                } else {
                    draws++;
                    statsA[2]++;
                    statsB[2]++;
                }
            }
        }
        LinearLayout metrics = horizontal();
        metrics.addView(metric("Partidos", String.valueOf(selectedMatches.size())));
        metrics.addView(metric("Deportes", String.valueOf(sports.size())));
        metrics.addView(metric("Pendientes", String.valueOf(selectedMatches.size() - played)));
        page.addView(metrics);
        LinearLayout resultsMetrics = horizontal();
        resultsMetrics.addView(metric("Con ganador", String.valueOf(played - draws)));
        resultsMetrics.addView(metric("Empates", String.valueOf(draws)));
        page.addView(resultsMetrics);

        Button add = action("Agregar fixture", false);
        LinearLayout.LayoutParams addParams = matchWrap();
        addParams.topMargin = dp(10);
        page.addView(add, addParams);
        add.setOnClickListener(view -> createMatch());

        page.addView(sectionTitle("Partidos del viernes"));
        if (selectedMatches.isEmpty()) {
            page.addView(emptyState("Todavía no hay fixtures para este viernes."));
        } else {
            for (JSONObject match : selectedMatches) {
                page.addView(matchCard(match));
            }
        }
        page.addView(sectionTitle("Resumen por equipo"));
        if (standings.isEmpty()) {
            page.addView(emptyState("Aún no hay resultados para este viernes."));
        } else {
            for (String team : standings.keySet()) {
                int[] stats = standings.get(team);
                page.addView(label(
                        team + "  ·  PJ " + stats[0]
                                + "  V " + stats[1]
                                + "  E " + stats[2]
                                + "  D " + stats[3]
                                + "  PF " + stats[4]
                                + "  PC " + stats[5],
                        14, TEXT, false));
            }
        }
        page.addView(sectionTitle("Historial"));
        Set<String> dates = new HashSet<>();
        int historyCount = 0;
        for (int i = 0; i < matches.length() && historyCount < 30; i++) {
            JSONObject match = matches.optJSONObject(i);
            if (match == null || formatIso(matchDate).equals(match.optString("match_date"))) {
                continue;
            }
            String date = match.optString("match_date");
            if (dates.add(date)) {
                page.addView(label(date, 15, ACCENT, true));
            }
            page.addView(matchCard(match));
            historyCount++;
        }
        if (historyCount == 0) {
            page.addView(emptyState("Todavía no hay otros partidos en el historial."));
        }
    }

    private View matchCard(JSONObject match) {
        LinearLayout card = card();
        card.addView(label(match.optString("sport"), 14, ACCENT, true));
        card.addView(label(
                shortTime(match.optString("start_time"))
                        + "  ·  " + match.optString("team_a")
                        + "  vs  " + match.optString("team_b"),
                16, TEXT, true));
        String result = match.isNull("score_a") || match.isNull("score_b")
                ? "Pendiente"
                : match.optInt("score_a") + "  –  " + match.optInt("score_b");
        card.addView(label(result, 15, MUTED, false));
        if (!match.optString("notes").isEmpty()
                && !"null".equals(match.optString("notes"))) {
            card.addView(label(match.optString("notes"), 14, MUTED, false));
        }

        LinearLayout buttons = horizontal();
        Button score = action("Resultado", true);
        Button remove = action("Eliminar", true);
        LinearLayout.LayoutParams scoreParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        scoreParams.setMargins(0, dp(8), dp(6), 0);
        buttons.addView(score, scoreParams);
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        removeParams.setMargins(dp(6), dp(8), 0, 0);
        buttons.addView(remove, removeParams);
        card.addView(buttons);

        score.setOnClickListener(view -> editMatchResult(match));
        remove.setOnClickListener(view -> confirm(
                "Eliminar partido",
                "Se eliminará este partido del historial.",
                "Eliminar",
                () -> mutate("Partido eliminado.", () -> {
                    JSONArray deleted = supabase.delete(
                            "tarde_deportiva_matches",
                            "id=eq." + SupabaseClient.encode(match.optString("id"))
                                    + "&select=id");
                    requireChanged(deleted, "No se eliminó el partido.");
                    return deleted;
                })));
        return card;
    }

    private void createMatch() {
        EditText sport = input("Fútbol, baloncesto, voleibol...", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText teamA = input("Equipo A", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText teamB = input("Equipo B", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText notes = input("Notas (opcional)", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        String[] startTime = {"13:00"};
        LinearLayout form = vertical();
        form.addView(sport, matchWrap());
        form.addView(teamA, matchWrap());
        form.addView(teamB, matchWrap());
        form.addView(notes, matchWrap());
        Button time = action("Hora: " + startTime[0], true);
        form.addView(time, matchWrap());
        time.setOnClickListener(view -> chooseTime(startTime[0], value -> {
            startTime[0] = value;
            time.setText("Hora: " + value);
        }));
        new AlertDialog.Builder(this)
                .setTitle("Nuevo fixture · " + formatDate(matchDate))
                .setView(withDialogPadding(form))
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String sportName = sport.getText().toString().trim();
                    String firstTeam = teamA.getText().toString().trim();
                    String secondTeam = teamB.getText().toString().trim();
                    if (sportName.isEmpty() || firstTeam.isEmpty() || secondTeam.isEmpty()) {
                        toast("Completa el deporte y los dos equipos.");
                        return;
                    }
                    String note = notes.getText().toString().trim();
                    mutate("Fixture agregado.", () -> {
                        JSONArray created = supabase.insert("tarde_deportiva_matches",
                                json("match_date", formatIso(matchDate),
                                        "sport", sportName,
                                        "start_time", startTime[0] + ":00",
                                        "team_a", firstTeam,
                                        "team_b", secondTeam,
                                        "notes", note.isEmpty() ? JSONObject.NULL : note));
                        requireChanged(created, "No se pudo guardar el fixture.");
                        return created;
                    });
                })
                .show();
    }

    private void editMatchResult(JSONObject match) {
        EditText scoreA = input(match.optString("team_a"), InputType.TYPE_CLASS_NUMBER);
        EditText scoreB = input(match.optString("team_b"), InputType.TYPE_CLASS_NUMBER);
        scoreA.setText(match.isNull("score_a") ? "0" : match.optString("score_a"));
        scoreB.setText(match.isNull("score_b") ? "0" : match.optString("score_b"));
        LinearLayout form = vertical();
        form.addView(scoreA, matchWrap());
        form.addView(scoreB, matchWrap());
        new AlertDialog.Builder(this)
                .setTitle("Resultado")
                .setView(withDialogPadding(form))
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    try {
                        int first = Integer.parseInt(scoreA.getText().toString().trim());
                        int second = Integer.parseInt(scoreB.getText().toString().trim());
                        if (first < 0 || second < 0) {
                            toast("El resultado no puede ser negativo.");
                            return;
                        }
                        mutate("Resultado guardado.", () -> {
                            JSONArray changed = supabase.update(
                                    "tarde_deportiva_matches",
                                    "id=eq." + SupabaseClient.encode(match.optString("id"))
                                            + "&select=id",
                                    json("score_a", first, "score_b", second));
                            requireChanged(changed, "No se guardó el resultado.");
                            return changed;
                        });
                    } catch (NumberFormatException error) {
                        toast("Escribe un resultado válido.");
                    }
                })
                .show();
    }

    private void exportInventory() {
        StringBuilder csv = new StringBuilder("id,codigo,disponible,prestado_a,fecha_prestamo\r\n");
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject ball = inventory.optJSONObject(i);
            if (ball != null) {
                csv.append(csvValue(ball.optString("id"))).append(',')
                        .append(csvValue(ball.optString("codigo"))).append(',')
                        .append(csvValue(String.valueOf(ball.optBoolean("disponible")))).append(',')
                        .append(csvValue(ball.optString("prestado_a"))).append(',')
                        .append(csvValue(ball.optString("fecha_prestamo"))).append("\r\n");
            }
        }
        pendingCsv = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "inventario_balones.csv");
        startActivityForResult(intent, 41);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 41 && resultCode == RESULT_OK && data != null
                && data.getData() != null && pendingCsv != null) {
            try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                if (output == null) {
                    throw new IOException("No se pudo abrir el archivo de destino.");
                }
                output.write(pendingCsv);
                toast("CSV exportado.");
            } catch (IOException error) {
                toast(error.getMessage());
            } finally {
                pendingCsv = null;
            }
        }
    }

    private String csvValue(String value) {
        String safe = value == null || "null".equals(value) ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private void refreshData(boolean showProgress) {
        if (showProgress) {
            setBusy(true);
        }
        executor.execute(() -> {
            try {
                loadDataFromWorker();
                runOnUiThread(() -> {
                    if (!supabase.isSignedIn()) {
                        renderLogin("La sesión venció. Inicia sesión de nuevo.");
                        return;
                    }
                    if (showProgress) {
                        setBusy(false);
                    }
                    renderShell();
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    if (showProgress) {
                        setBusy(false);
                    }
                    if (!supabase.isSignedIn()) {
                        renderLogin(error.getMessage());
                    } else if (shell == null) {
                        renderShell();
                        toast(error.getMessage());
                    } else {
                        toast(error.getMessage());
                    }
                });
            }
        });
    }

    private void loadDataFromWorker() throws IOException {
        inventory = supabase.getInventory();
        calendarEvents = supabase.getCalendarEvents();
        matches = supabase.getMatches();
    }

    private void mutate(String successMessage, IoAction<JSONArray> action) {
        setBusy(true);
        executor.execute(() -> {
            try {
                action.run();
                loadDataFromWorker();
                runOnUiThread(() -> {
                    setBusy(false);
                    toast(successMessage);
                    renderShell();
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    if (!supabase.isSignedIn()) {
                        renderLogin(error.getMessage());
                    } else {
                        toast(error.getMessage());
                    }
                });
            }
        });
    }

    private void signOut() {
        setBusy(true);
        executor.execute(() -> {
            IOException failure = null;
            try {
                supabase.signOut();
            } catch (IOException error) {
                failure = error;
            }
            IOException result = failure;
            runOnUiThread(() -> {
                setBusy(false);
                renderLogin(result == null ? null
                        : "Se cerró la sesión localmente; no se pudo revocar en el servidor.");
            });
        });
    }

    private void setBusy(boolean value) {
        if (busy != null) {
            busy.setVisibility(value ? View.VISIBLE : View.GONE);
        }
    }

    private void animatePageEntrance() {
        if (page == null) {
            return;
        }
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(dp(9));
            child.animate()
                    .alpha(1f)
                    .translationY(0)
                    .setStartDelay(Math.min(i * 28L, 168L))
                    .setDuration(240)
                    .start();
        }
    }

    private void applySystemInsets(
            View root, int baseLeft, int baseTop, int baseRight, int baseBottom) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(
                    safe.left + baseLeft,
                    safe.top + baseTop,
                    safe.right + baseRight,
                    safe.bottom + baseBottom);
            return insets;
        });
    }

    private void requireChanged(JSONArray rows, String message) throws IOException {
        if (rows == null || rows.length() == 0) {
            throw new IOException(message);
        }
    }

    private void confirm(
            String title, String message, String positive, Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Volver", null)
                .setPositiveButton(positive, (dialog, which) -> onConfirm.run())
                .show();
    }

    private void chooseDate(Date initial, DateSelection callback, boolean fridayOnly) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(initial);
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (picker, year, month, day) -> {
                    Calendar selected = Calendar.getInstance();
                    selected.set(year, month, day, 12, 0, 0);
                    selected.set(Calendar.MILLISECOND, 0);
                    Date value = selected.getTime();
                    if (fridayOnly && dayOfWeek(value) != Calendar.FRIDAY) {
                        toast("Selecciona un viernes.");
                        return;
                    }
                    callback.onSelected(value);
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH));
        dialog.show();
    }

    private void chooseTime(String initial, TimeSelection callback) {
        int[] parts = parseTime(initial);
        new TimePickerDialog(this,
                (picker, hour, minute) ->
                        callback.onSelected(String.format(Locale.US, "%02d:%02d", hour, minute)),
                parts[0], parts[1], true).show();
    }

    private int[] parseTime(String value) {
        try {
            String[] parts = value.split(":");
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException error) {
            return new int[]{8, 0};
        }
    }

    private Date nextFriday(Date from) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(from);
        int days = (Calendar.FRIDAY - calendar.get(Calendar.DAY_OF_WEEK) + 7) % 7;
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return calendar.getTime();
    }

    private int dayOfWeek(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar.get(Calendar.DAY_OF_WEEK);
    }

    private String formatIso(Date value) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(value);
    }

    private String formatDate(Date value) {
        return new SimpleDateFormat("EEE, d MMM yyyy", new Locale("es")).format(value);
    }

    private String isoTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                .format(new Date());
    }

    private String shortTime(String value) {
        return value == null || value.length() < 5
                ? value == null ? "" : value
                : value.substring(0, 5);
    }

    private List<JSONObject> ballsWithStatus(boolean available) {
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < inventory.length(); i++) {
            JSONObject ball = inventory.optJSONObject(i);
            if (ball != null && ball.optBoolean("disponible", true) == available) {
                result.add(ball);
            }
        }
        return result;
    }

    private int countBalls(boolean available) {
        return ballsWithStatus(available).size();
    }

    private List<JSONObject> matchesForDate(String date) {
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < matches.length(); i++) {
            JSONObject match = matches.optJSONObject(i);
            if (match != null && date.equals(match.optString("match_date"))) {
                result.add(match);
            }
        }
        return result;
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

    private LinearLayout heading(String title, String subtitle) {
        LinearLayout block = vertical();
        TextView heading = label(title, 25, TEXT, true);
        block.addView(heading);
        TextView detail = label(subtitle, 14, MUTED, false);
        LinearLayout.LayoutParams params = wrap();
        params.topMargin = dp(5);
        block.addView(detail, params);
        LinearLayout.LayoutParams blockParams = matchWrap();
        blockParams.bottomMargin = dp(20);
        block.setLayoutParams(blockParams);
        return block;
    }

    private TextView sectionTitle(String value) {
        TextView title = label(value, 18, TEXT, true);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(22);
        params.bottomMargin = dp(10);
        title.setLayoutParams(params);
        return title;
    }

    private View metric(String title, String value) {
        LinearLayout panel = vertical();
        panel.setPadding(dp(12), dp(14), dp(12), dp(14));
        panel.setBackground(surface(SURFACE, 18));
        panel.setElevation(dp(2));
        panel.addView(label(value, 23, ACCENT, true));
        panel.addView(label(title, 12, MUTED, false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(3), 0, dp(3), dp(3));
        panel.setLayoutParams(params);
        return panel;
    }

    private LinearLayout card() {
        LinearLayout card = vertical();
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(surface(SURFACE, 18));
        card.setElevation(dp(2));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(9);
        card.setLayoutParams(params);
        return card;
    }

    private View ballCard(JSONObject ball, boolean returnAction) {
        LinearLayout card = card();
        boolean available = ball.optBoolean("disponible", true);
        card.addView(label(ball.optString("codigo"), 17, TEXT, true));
        card.addView(label(available ? "Disponible"
                        : "Prestado a " + ball.optString("prestado_a", "Sin nombre"),
                14, available ? ACCENT : MUTED, false));
        if (!available) {
            String borrowedAt = ball.optString("fecha_prestamo", "");
            if (!borrowedAt.isEmpty() && !"null".equals(borrowedAt)) {
                card.addView(label("Desde " + borrowedAt.replace('T', ' '), 12, MUTED, false));
            }
        }
        if (returnAction && !available) {
            Button button = action("Registrar devolución", true);
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(8);
            card.addView(button, params);
            button.setOnClickListener(view -> returnBall(ball));
        }
        return card;
    }

    private TextView emptyState(String value) {
        TextView message = label(value, 14, MUTED, false);
        message.setPadding(dp(14), dp(14), dp(14), dp(14));
        message.setBackground(surface(SURFACE, 16));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(8);
        message.setLayoutParams(params);
        return message;
    }

    private Spinner spinner(String[] options) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackgroundTintList(ColorStateList.valueOf(SURFACE_LIGHT));
        return spinner;
    }

    private EditText input(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(MUTED);
        field.setTextColor(TEXT);
        field.setTextSize(15);
        field.setInputType(inputType);
        field.setPadding(dp(14), dp(10), dp(14), dp(10));
        field.setBackground(surface(SURFACE_LIGHT, 14));
        return field;
    }

    private Button action(String title, boolean secondary) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(secondary ? TEXT : Color.WHITE);
        int fill = secondary ? SURFACE_LIGHT : ACCENT;
        button.setBackground(ripple(fill, 14,
                secondary ? Color.rgb(215, 225, 214) : Color.rgb(34, 88, 52)));
        button.setMinHeight(dp(44));
        button.setPadding(dp(14), dp(6), dp(14), dp(6));
        button.setElevation(dp(1));
        return button;
    }

    private LinearLayout navigationItem(String title, int icon, boolean selected) {
        LinearLayout item = vertical();
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(2), dp(3), dp(2), dp(3));
        item.setContentDescription(title);

        FrameLayout iconFrame = new FrameLayout(this);
        iconFrame.setBackground(surface(selected ? ACCENT_LIGHT : SURFACE, 14));
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImageTintList(ColorStateList.valueOf(selected ? ACCENT : MUTED));
        FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(
                dp(22), dp(22), Gravity.CENTER);
        iconFrame.addView(image, imageParams);
        item.addView(iconFrame, new LinearLayout.LayoutParams(dp(42), dp(34)));

        TextView caption = label(title, 10, selected ? ACCENT : MUTED, selected);
        caption.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams captionParams = wrap();
        captionParams.topMargin = dp(2);
        item.addView(caption, captionParams);
        item.setForeground(new RippleDrawable(
                ColorStateList.valueOf(0x24307146), null, null));
        return item;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), Color.rgb(229, 234, 226));
        return drawable;
    }

    private GradientDrawable gradient(int start, int end, int radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private RippleDrawable ripple(int color, int radius, int rippleColor) {
        return new RippleDrawable(
                ColorStateList.valueOf(rippleColor), surface(color, radius), null);
    }

    private ImageView logoImage() {
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(5), dp(5), dp(5), dp(5));
        logo.setBackground(surface(SURFACE, 18));
        logo.setElevation(dp(2));
        return logo;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private View withDialogPadding(View content) {
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(dp(20), dp(8), dp(20), dp(2));
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    private void toast(String message) {
        Toast.makeText(this,
                message == null || message.isEmpty() ? "Ocurrió un error." : message,
                Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (supabase == null || !supabase.isSignedIn()) {
            super.onBackPressed();
            return;
        }
        confirm(
                "Cerrar la aplicación",
                "Se cerrará tu sesión en este dispositivo.",
                "Cerrar",
                () -> {
                    setBusy(true);
                    executor.execute(() -> {
                        IOException failure = null;
                        try {
                            supabase.signOut();
                        } catch (IOException error) {
                            failure = error;
                        }
                        IOException result = failure;
                        runOnUiThread(() -> {
                            setBusy(false);
                            if (result != null) {
                                toast("Se cerró la sesión localmente.");
                            }
                            finish();
                        });
                    });
                });
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private interface IoAction<T> {
        T run() throws IOException;
    }

    private interface DateSelection {
        void onSelected(Date value);
    }

    private interface TimeSelection {
        void onSelected(String value);
    }
}
