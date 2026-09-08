// Voice input provider protocol. Package/interface names must stay in sync with
// the client in :app and any external provider plugin (e.g. BiBi-Keyboard).
// New methods MUST be appended: transaction codes are positional.
package org.fcitx.fcitx5.android.common.ipc;

import android.os.Bundle;
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback;

interface IVoiceInputProvider {
    boolean isAvailable();

    Bundle getPreferredConfig();

    oneway void configure(in Bundle config);

    oneway void startSession(IVoiceInputCallback callback);

    oneway void feedAudio(in byte[] pcm, int offset, int len, long ptsMs);

    oneway void endStream();

    oneway void cancelSession();

    oneway void stopSession();

    // ---- configuration surface rendered by the host app ----

    // Current settings as a Bundle (vendor, api keys, model/resource ids, ...).
    Bundle getSettings();

    // Persist the given settings; keys absent from the Bundle are left unchanged.
    oneway void setSettings(in Bundle settings);

    // Selectable model catalog: {ids: String[], labels: String[]}.
    Bundle getModelCatalog();

    // Blocking online probe of the catalog: {<resourceId>: "granted"|"not_granted"|"failed"}.
    Bundle probeResources();

    // Blocking online model list fetch: {models: String[]} or {error: String}.
    Bundle fetchModels();

    boolean hasMicPermission();

    // Fully-qualified activity in the plugin that asks for RECORD_AUDIO, or null.
    String micPermissionActivity();

    // Recent session diagnostics for troubleshooting (host app renders it).
    String getDiagnostics();

    // JSON array [{id, name}] of all supported vendors.
    String getVendorList();

    // JSON descriptor of one vendor's settings form fields.
    String getVendorConfig(String vendorId);

    // OpenAI-compatible GET {base}/models: {models: String[]} or {error: String}.
    Bundle fetchModelsAt(String baseUrl, String apiKey);
}
