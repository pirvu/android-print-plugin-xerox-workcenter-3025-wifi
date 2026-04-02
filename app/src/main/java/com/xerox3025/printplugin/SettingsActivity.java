package com.xerox3025.printplugin;

import android.os.Bundle;
import android.text.InputType;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
            getSupportActionBar().setSubtitle(R.string.settings_subtitle);
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            // Force numeric keyboard for IP and port fields
            EditTextPreference ipPref = findPreference("printer_ip");
            if (ipPref != null) {
                ipPref.setOnBindEditTextListener(editText ->
                        editText.setInputType(InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_URI));
            }

            EditTextPreference portPref = findPreference("printer_port");
            if (portPref != null) {
                portPref.setOnBindEditTextListener(editText ->
                        editText.setInputType(InputType.TYPE_CLASS_NUMBER));
            }
        }
    }
}
