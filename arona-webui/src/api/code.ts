declare interface codeMessageMapTypes {
  200: string;
  400: string;
  401: string;
  403: string;
  404: string;
  405: string;
  500: string;
  601: string;
  [key: string]: string;
}

const codeMessageMap: codeMessageMapTypes = {
  200: "成功",
  400: "[400]:请求参数错误",
  401: "[401]:账户未登录",
  403: "[403]:拒绝访问",
  404: "[404]:请求路径错误",
  405: "[405]:请求方法错误",
  500: "[500]:服务异常, 请稍后重试",
  601: "操作失败",
};

const showCodeMessage = (code: number | string): string => {
  // 之前用 JSON.stringify(数字) 会得到带引号的字符串, 在 map 上找不到 key.
  return codeMessageMap[String(code)] || "网络连接异常,请稍后再试!";
};

export default showCodeMessage;
