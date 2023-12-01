import useSettingStore from "@/store/setting";
import { ApiServiceAdapter } from "@/interface/http";
import AronaAdapter from "@/api/adapter/aronaAdapter";

const ServiceHandler: ProxyHandler<ApiServiceAdapter> = {
  get(_, key) {
    const settingStore = useSettingStore();
    const adapter = AronaAdapter;
    return Reflect.get(adapter, key);
  },
};

const service = new Proxy(AronaAdapter, ServiceHandler);

export default service;
