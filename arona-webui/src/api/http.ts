import useSettingStore from "@/store/setting";
import { ApiServiceAdapter, ServerResponse } from "@/interface/http";
import AronaAdapter from "@/api/adapter/aronaAdapter";
import { HTTP_OK } from "@/constant";
import type { BusinessError, FieldError, FieldErrorPayload } from "@/interface/pluginSchema";

const ServiceHandler: ProxyHandler<ApiServiceAdapter> = {
  get(_, key) {
    const settingStore = useSettingStore();
    const adapter = AronaAdapter;
    return Reflect.get(adapter, key);
  },
};

const service = new Proxy(AronaAdapter, ServiceHandler);

function extractFieldErrors(data: unknown): FieldError[] | undefined {
  if (data && typeof data === "object" && Array.isArray((data as FieldErrorPayload).fieldErrors)) {
    return (data as FieldErrorPayload).fieldErrors;
  }
  return undefined;
}

/**
 * 业务失败时 reject 一个结构化的 [BusinessError] (含 message 与可选 fieldErrors), 而非裸 message,
 * 便于动态表单等调用方把字段级错误绑定到具体输入控件. 现有 `.catch(() => ...)` 不受影响.
 */
export function simplifiedApiService<T>(row: Promise<ServerResponse<T>>) {
  return new Promise<T>((resolve, reject) => {
    row
      .then((res) => {
        if (res.code === HTTP_OK) {
          resolve(res.data as T);
        } else {
          const error: BusinessError = {
            message: res.message,
            fieldErrors: extractFieldErrors(res.data),
          };
          reject(error);
        }
      })
      .catch(reject);
  });
}
export default service;
