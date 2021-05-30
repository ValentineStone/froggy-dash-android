package com.example.froggydash;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
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

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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

        value("/public", this::log);
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
            subscribe("/users/" + user.getUid(), dbUser -> {
                log("loaded user", user.getUid());
                LinearLayout userView = spawnButton("User " + user.getUid(), treeRootLayout);
                subscribeChilds(dbUser, "multifrogs", multifrog -> {
                    log("loaded multifrog", multifrog.getKey());
                    LinearLayout miltiView = spawnButton("Multifrog " + multifrog.getKey(), userView);
                    subscribeChilds(multifrog, "frogs", frog -> {
                        log("loaded frog", frog.getKey());
                        LinearLayout frogView = spawnButton("Frog " + frog.getKey(), miltiView);
                        subscribeChilds(frog, "sensors", sensor -> {
                            log("loaded sensor", sensor.getKey());
                            spawnButton("Sensor " + sensor.getKey(), frogView);
                        });
                        subscribeChilds(frog, "sensors", "readings", readings -> {
                            int count = ((Map<String,String>)readings.getValue()).size();
                            log("loaded " + count + " readings for", readings.getKey());
                        });
                    });
                });
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

    void log(Object arg) {
        textView.append("> ");
        textView.append(arg == null ? "null" : arg.toString());
        textView.append("\n");
    }

    LinearLayout spawnButton(String text, LinearLayout root) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        LinearLayout children = new LinearLayout(this);
        children.setPadding(50, 0,0,0);
        children.setOrientation(LinearLayout.VERTICAL);

        Button button = new Button(this);
        button.setText(text);
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

    Runnable value(String path, Consumer<DataSnapshot> callback) {
        //log("loading", path, "from db...");
        DatabaseReference ref = database.getReference(path);
        ValueEventListener listener =new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                //log("loaded", path);
                callback.accept(snap);
            }
            @Override
            public void onCancelled(DatabaseError error) {
                //log("error loading:", path, error.toString());
            }
        };
        ref.addListenerForSingleValueEvent(listener);
        return () -> {
            log("canceled loading", path, "from db...");
            ref.removeEventListener(listener);
        };
    }

    Runnable subscribe(String path, Consumer<DataSnapshot> callback) {
        //log("subscribed to", path, "from db...");
        DatabaseReference ref = database.getReference(path);
        ValueEventListener listener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                //log("updated", path);
                callback.accept(snap);
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
        };
    }

    Map<String, String> asMap(Object obj, String ...path) {
        Map<String, Object> ret = (Map<String, Object>)obj;
        for (String name : path)
            ret = (Map<String, Object>)ret.get(name);
        return (Map<String, String>)(Object)ret;
    }

    Runnable valueChilds(DataSnapshot parent, String path, Consumer<DataSnapshot> callback) {
        return valueChilds(parent,path,path,callback);
    }

    Runnable subscribeChilds(DataSnapshot parent, String path, Consumer<DataSnapshot> callback) {
        return subscribeChilds(parent,path,path,callback);
    }

    Runnable valueChilds(DataSnapshot parent, String path, String rootPath, Consumer<DataSnapshot> callback) {
        Map<String, String> map = asMap(parent.getValue(), path);
        Runnable[] unsubscribers = new Runnable[map.size()];
        int i = 0;
        for (String uuid: map.keySet())
            unsubscribers[i++] = value("/" + rootPath + "/" + uuid, callback);
        return () -> { for (Runnable unsubber : unsubscribers) unsubber.run(); };
    }

    Runnable subscribeChilds(DataSnapshot parent, String path, String rootPath, Consumer<DataSnapshot> callback) {
        Map<String, String> map = asMap(parent.getValue(), path);
        Runnable[] unsubscribers = new Runnable[map.size()];
        int i = 0;
        for (String uuid: map.keySet())
            unsubscribers[i++] = subscribe("/" + rootPath + "/" + uuid, callback);
        return () -> { for (Runnable unsubber : unsubscribers) unsubber.run(); };
    }
}