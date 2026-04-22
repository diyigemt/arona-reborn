import axios, { AxiosError, AxiosInstance } from "axios";
import { ApiServiceAdapter, IRequestConfig, IResponse, IResponseError, ServerResponse } from "@/interface/http";
import { HTTP_OK } from "@/constant";
import { errorMessage, infoMessage, warningMessage } from "@/utils/message";
import showCodeMessage from "@/api/code";
import { formatJsonToUrlParams, instanceObject } from "@/utils/format";
import useBaseStore from "@/store/base";

const BASE_PREFIX = import.meta.env.VITE_API_BASEURL;

// 创建实例
const axiosInstance: AxiosInstance = axios.create({
  baseURL: BASE_PREFIX,
  timeout: 1000 * 30,
  headers: {
    "Content-Type": "application/json",
  },
});

// 请求拦截器
axiosInstance.interceptors.request.use(
  // @ts-ignore
  (config: IRequestConfig) => {
    config.headers = config.headers ?? {};
    const { token } = useBaseStore();
    if (token) {
      Reflect.set(config.headers, "Authorization", `Bearer ${token}`);
    } else {
      Reflect.deleteProperty(config.headers, "Authorization");
    }
    config.showResponseError = config.showResponseError ?? true;
    config.showServerResponseError = config.showServerResponseError ?? true;
    return config;
  },
  (error: AxiosError) => {
    return Promise.reject(error);
  },
);

// 响应拦截器
axiosInstance.interceptors.response.use(
  (response: IResponse) => {
    const { config } = response;
    if (config.isBlob) {
      return response;
    }
    if (response.status === HTTP_OK) {
      const resp = response.data as ServerResponse<unknown>;
      if (resp && resp.code !== HTTP_OK && response.config.showServerResponseError) {
        // 不再原样回显后端 message, 避免异常细节泄漏; 业务码映射到本地化文案.
        // 如调用方需要原始 message (例如内容审核拒绝原因), 可关闭 showServerResponseError 自行处理.
        warningMessage(showCodeMessage(resp.code));
      }
      return response.data;
    }
    infoMessage(JSON.stringify(response.status));
    return response;
  },
  (error: IResponseError) => {
    const { response, config } = error;
    if (response) {
      if (config && config.showResponseError) {
        errorMessage(showCodeMessage(response.status));
      }
      return Promise.reject(response.data);
    }
    if (config && config.showResponseError) {
      warningMessage("网络连接异常,请稍后再试!");
    }
    return Promise.reject(error);
  },
);

const AronaService: ApiServiceAdapter = {
  upload(url: string, file: FormData | File) {
    if (Object.hasOwn(file, "get")) {
      const fileName = encodeURI(((file as FormData).get("file") as File).name);
      return axiosInstance({
        url,
        method: "POST",
        data: file,
        headers: buildFileUploadHeader(fileName),
      }) as unknown as Promise<ServerResponse<string>>;
    }
    const data = new FormData();
    data.set("file", file as Blob);
    const fileName = encodeURI((file as File).name);
    return axiosInstance({
      url,
      method: "POST",
      data,
      headers: buildFileUploadHeader(fileName),
    }) as unknown as Promise<ServerResponse<string>>;
  },
  download(id: string) {
    const config: IRequestConfig = {
      url: "/file/image",
      params: {
        id,
      },
      method: "GET",
      responseType: "blob",
      isBlob: true,
    };
    return axiosInstance(config);
  },
  raw<T>(config: IRequestConfig): Promise<ServerResponse<T>> {
    return axiosInstance(config) as unknown as Promise<ServerResponse<T>>;
  },
  urlDownload(url: string, data: instanceObject) {
    window.location.href = `${BASE_PREFIX}/${url}?${formatJsonToUrlParams(data)}`;
  },
};

function buildFileUploadHeader(fileName: string) {
  return {
    "Content-Type": "multipart/form-data",
    "arona-file-name": fileName,
  };
}

export default AronaService;
