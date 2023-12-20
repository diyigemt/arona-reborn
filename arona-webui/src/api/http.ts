import useSettingStore from "@/store/setting";
import { ApiServiceAdapter, ServerResponse } from "@/interface/http";
import AronaAdapter from "@/api/adapter/aronaAdapter";
import { HTTP_OK } from "@/constant";

const ServiceHandler: ProxyHandler<ApiServiceAdapter> = {
  get(_, key) {
    const settingStore = useSettingStore();
    const adapter = AronaAdapter;
    return Reflect.get(adapter, key);
  },
};

const service = new Proxy(AronaAdapter, ServiceHandler);

export function simplifiedApiService<T>(row: Promise<ServerResponse<T>>) {
  return new Promise<T>((resolve, reject) => {
    row
      .then((res) => {
        if (res.code === HTTP_OK) {
          resolve(res.data);
        } else {
          reject(res.message);
        }
      })
      .catch(reject);
  });
}
export default service;
