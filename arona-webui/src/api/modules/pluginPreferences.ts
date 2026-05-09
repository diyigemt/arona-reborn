import service, { simplifiedApiService } from "@/api/http";
import { PluginConfigSchema } from "@/interface/pluginSchema";

// eslint-disable-next-line import/prefer-default-export
export const PluginPreferenceApi = {
  fetchPluginPreference(pluginId: string, preferenceKey: string) {
    return simplifiedApiService(
      service.raw<string>({
        url: "/plugin/preference",
        method: "GET",
        params: {
          id: pluginId,
          key: preferenceKey,
        },
      }),
    );
  },
  fetchPluginPreferences(pluginId: string) {
    return simplifiedApiService(
      service.raw<string[]>({
        url: "/plugin/preference",
        method: "GET",
        params: {
          id: pluginId,
        },
      }),
    );
  },
  updatePluginPreference(pluginId: string, preferenceKey: string, preference: string | object) {
    return simplifiedApiService(
      service.raw<void>({
        url: "/plugin/preference",
        method: "POST",
        data: {
          id: pluginId,
          key: preferenceKey,
          value: typeof preference === "string" ? preference : JSON.stringify(preference),
        },
      }),
    );
  },
  // 后端在配置未注册时返回 success(null), 调用方需自行处理 null.
  fetchPluginConfigSchema(pluginId: string, preferenceKey: string) {
    return simplifiedApiService(
      service.raw<PluginConfigSchema | null>({
        url: "/plugin/preference/schema",
        method: "GET",
        params: {
          id: pluginId,
          key: preferenceKey,
        },
      }),
    );
  },
};
