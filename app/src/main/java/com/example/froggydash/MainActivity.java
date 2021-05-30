package com.example.froggydash;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class MainActivity extends AppCompatActivity {
    TextView textView;
    Button loginLogoutButton;
    LinearLayout treeRootLayout;
    FirebaseAuth auth;
    FirebaseUser user;
    FirebaseDatabase database;
    Runnable cleanupOnLogout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loginLogoutButton = findViewById(R.id.loginLogoutButton);
        textView = findViewById(R.id.textView);
        treeRootLayout = findViewById(R.id.treeRootLayout);
        loginLogoutButton.setOnClickListener(this::onLoginLogoutButtonClick);
        textView.setMovementMethod(new ScrollingMovementMethod());

        database = FirebaseDatabase.getInstance();
        auth = FirebaseAuth.getInstance();
        auth.addAuthStateListener(this::onAuthStateChanged);
    }

    // callbacks

    public void onAuthStateChanged(FirebaseAuth auth) {
        user = auth.getCurrentUser();
        loginLogoutButton.setVisibility(View.VISIBLE);
        if (user == null) {
            log("Auth state changed: logged out");
            loginLogoutButton.setText("Log in");
            if (cleanupOnLogout != null)
                cleanupOnLogout.run();
        }
        else {
            log("Auth state changed: logged in " + user.getUid());
            loginLogoutButton.setText("Log out");
            cleanupOnLogout = subscribe("/users/" + user.getUid(), dbUser -> {
                log("loaded user", user.getUid());
                LinearLayout userView = spawnButton("User " + user.getUid(), treeRootLayout);
                return chain(
                    () -> treeRootLayout.removeView((View)userView.getParent()),
                    subscribeChilds(dbUser, "multifrogs", multifrog -> {
                        log("loaded multifrog", multifrog.getKey());
                        LinearLayout miltiView = spawnButton("Multifrog " + multifrog.getKey(), userView);
                        return chain(
                            () -> userView.removeView((View)miltiView.getParent()),
                            subscribeChilds(multifrog, "frogs", frog -> {
                                log("loaded frog", frog.getKey());
                                LinearLayout frogView = spawnButton("Frog " + frog.getKey(), miltiView);
                                Map<String,Object> sensorsMap = (Map<String,Object>)frog.child("sensors").getValue();
                                Map<String,LinearLayout> sensorViewsMap = new HashMap<>();
                                sensorsMap.keySet().forEach(uuid -> sensorViewsMap.put(uuid,
                                    spawnButton("Sensor " + uuid, frogView, true)));
                                return chain(
                                    () -> miltiView.removeView((View)frogView.getParent()),
                                    subscribeChilds(frog, "sensors", sensor -> {
                                        log("loaded sensor", sensor.getKey());
                                        TextView label = new TextView(this);
                                        label.setText(sensor.getValue().toString());
                                        sensorViewsMap.get(sensor.getKey()).addView(label, 0);
                                        return () -> sensorViewsMap.get(sensor.getKey()).removeView(label);
                                    }),
                                    subscribeChilds(frog, "sensors", "readings", readings -> {
                                        Map<String,Long> readingsMap = (Map<String,Long>)readings.getValue();
                                        ArrayList<Long> readingValues = new ArrayList<>(new TreeMap<>(readingsMap).values());
                                        int count = readingValues.size();
                                        log("loaded " + count + " readings for", readings.getKey());

                                        int drawStart = Math.max(count - 9, 0);
                                        int drawCount = count - drawStart;
                                        DataPoint[] points = new DataPoint[drawCount];
                                        for (int i = 0; i < drawCount; i++)
                                            points[i] = new DataPoint(i, readingValues.get(drawStart + i));
                                        GraphView graph = new GraphView(this);
                                        graph.setVisibility(View.VISIBLE);
                                        LineGraphSeries<DataPoint> series = new LineGraphSeries<>(points);
                                        graph.addSeries(series);
                                        graph.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 500));
                                        sensorViewsMap.get(readings.getKey()).addView(graph);
                                        return () -> sensorViewsMap.get(readings.getKey()).removeView(graph);
                                    })
                                );
                            })
                        );
                    })
                );
            });
        }
    }

    public void onLoginLogoutButtonClick(View view) {
        if (user == null)
            requestEmailAndPassword(auth::signInWithEmailAndPassword);
        else
            requestLogoutConfirmation(auth::signOut);
    }

    // actions

    void log(String ...args) {
        textView.append("> ");
        textView.append(String.join(" ", args));
        textView.append("\n");
    }

    LinearLayout spawnButton(String text, LinearLayout root, boolean hidden) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        LinearLayout children = new LinearLayout(this);
        children.setPadding(50, 0,0,0);
        children.setOrientation(LinearLayout.VERTICAL);
        children.setVisibility(hidden ? View.GONE : View.VISIBLE);

        Button button = new Button(this);
        button.setText(text);
        button.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        button.setOnClickListener(view -> {
            if (children.getVisibility() == View.VISIBLE)
                children.setVisibility(View.GONE);
            else
                children.setVisibility(View.VISIBLE);
        });

        layout.addView(button);
        layout.addView(children);
        root.addView(layout);
        return children;
    }

    LinearLayout spawnButton(String text, LinearLayout root) {
        return spawnButton(text, root, false);
    }

    void requestEmailAndPassword(BiConsumer<String,String> callback) {
        LinearLayout loginLayout = new LinearLayout(this);
        loginLayout.setOrientation(LinearLayout.VERTICAL);
        EditText emailInput = new EditText(this);
        emailInput.setHint("Email");
        EditText passwordInput = new EditText(this);
        passwordInput.setHint("Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        loginLayout.addView(emailInput);
        loginLayout.addView(passwordInput);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Log in")
                .setView(loginLayout)
                .setPositiveButton("OK", (DialogInterface dialog, int which) ->
                    callback.accept(emailInput.getText().toString(), passwordInput.getText().toString()))
                .setNegativeButton("Cancel", (DialogInterface dialog, int which) ->
                    dialog.cancel());
        builder.show();
    }

    void requestLogoutConfirmation(Runnable runable) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Are you sure you want ot log out?")
                .setPositiveButton("OK", (DialogInterface dialog, int which) ->
                        runable.run())
                .setNegativeButton("Cancel", (DialogInterface dialog, int which) ->
                        dialog.cancel());
        builder.show();
    }

    // Firebase helper functions

    Runnable chain(Runnable ...runnables) {
        return () -> { for (Runnable runnable : runnables) runnable.run(); };
    }

    Runnable subscribe(String path, Function<DataSnapshot, Runnable> callback) {
        //log("subscribed to", path, "from db...");
        DatabaseReference ref = database.getReference(path);
        AtomicReference<Runnable> uneffect = new AtomicReference<Runnable>();
        ValueEventListener listener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                //log("updated", path);
                if (uneffect.get() != null) uneffect.get().run();
                uneffect.set(callback.apply(snap));
            }
            @Override
            public void onCancelled(DatabaseError error) {
                //log("error subscribing to:", path, error.toString());
            }
        };
        ref.addValueEventListener(listener);
        return () -> {
            log("unsubscribed from", path, "from db...");
            ref.removeEventListener(listener);
            if (uneffect.get() != null) uneffect.get().run();
            uneffect.set(null);
        };
    }

    Runnable subscribeChilds(DataSnapshot parent, String path, Function<DataSnapshot, Runnable> callback) {
        return subscribeChilds(parent,path,path,callback);
    }

    Runnable subscribeChilds(DataSnapshot parent, String path, String rootPath, Function<DataSnapshot, Runnable> callback) {
        Map<String, String> map = (Map<String, String>)parent.child(path).getValue();
        Runnable[] unsubs = new Runnable[map.size()];
        int i = 0;
        for (String uuid: map.keySet())
            unsubs[i++] = subscribe("/" + rootPath + "/" + uuid, callback);
        return chain(unsubs);
    }
}