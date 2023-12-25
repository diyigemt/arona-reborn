import service, { simplifiedApiService } from "@/api/http";
import { Policy, PolicyResource } from "@/interface";

function splitAndSort(data: string[]): string[] {
  // 分割数据并按":"分组
  const groupedData = data.reduce(
    (acc, item) => {
      const arr = item.split(":");
      const prefix = arr.splice(0, 1)[0];
      const last = arr[1] ? arr.join(":") : "";
      if (!acc[prefix]) {
        acc[prefix] = [];
      }
      acc[prefix].push(last);
      return acc;
    },
    {} as Record<string, string[]>,
  );
  // 将分组后的对象转换为数组，并按长度倒序排序
  const entry = Object.entries(groupedData);
  entry.forEach((it) => {
    it[1] = it[1].filter((f) => f);
    it[1].sort((a, b) => a.length - b.length);
  });
  return entry.map(([key, value]) => value.map((it) => `${key}:${it}`)).flat();
}

// eslint-disable-next-line import/prefer-default-export
export const PolicyApi = {
  fetchUserDefinePolicy() {
    return service.raw<Policy[]>({
      url: "",
    });
  },
  fetchResources() {
    return simplifiedApiService(
      service.raw<PolicyResource[]>({
        url: "/policy/resources",
        method: "GET",
      }),
    ).then((data) => {
      data.push("*");
      return splitAndSort(data);
    });
  },
};
