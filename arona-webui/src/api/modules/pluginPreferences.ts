import service from "@/api/http";

// eslint-disable-next-line import/prefer-default-export
export const PluginPreferenceApi = {
  fetchPluginPreference(pluginId: string, preferenceKey: string) {
    return service.raw<string>({
      url: "/plugin/preference",
      method: "GET",
      params: {
        id: pluginId,
        key: preferenceKey,
      },
    });
  },
  fetchPluginPreferences(pluginId: string) {
    return service.raw<string[]>({
      url: "/plugin/preference",
      method: "GET",
      params: {
        id: pluginId,
      },
    });
  },
  savePluginPreference(pluginId: string, preferenceKey: string, preference: string) {
    return service.raw<void>({
      url: "/plugin/preference",
      method: "POST",
      data: {
        pluginId,
        preferenceKey,
        preference,
      },
    });
  },
};
