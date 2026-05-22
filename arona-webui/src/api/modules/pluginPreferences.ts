import service, { simplifiedApiService } from "@/api/http";
import { PluginConfigSchema } from "@/interface/pluginSchema";

// eslint-disable-next-line import/prefer-default-export
export const PluginPreferenceApi = {
  fetchPluginPreference(pluginId: string, preferenceKey: string) {
    return simplifiedApiService(
      service.raw<Record<string, unknown> | null>({
        url: "/plugin/preference",
        method: "GET",
        params: {
          id: pluginId,
          key: preferenceKey,
        },
      }),
    );
  },
  // 后端返回该插件在当前 user 下所有 configKey 的对象树, 不再是 JSON 字符串数组.
  fetchPluginPreferences(pluginId: string) {
    return simplifiedApiService(
      service.raw<Record<string, Record<string, unknown>> | null>({
        url: "/plugin/preference",
        method: "GET",
        params: {
          id: pluginId,
        },
      }),
    );
  },
  updatePluginPreference(pluginId: string, preferenceKey: string, preference: Record<string, unknown>) {
    return simplifiedApiService(
      service.raw<void>({
        url: "/plugin/preference",
        method: "POST",
        data: {
          id: pluginId,
          key: preferenceKey,
          value: preference,
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
