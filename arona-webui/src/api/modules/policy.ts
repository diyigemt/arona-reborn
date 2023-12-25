import service, { simplifiedApiService } from "@/api/http";
import { Policy, PolicyResource } from "@/interface";

function splitAndSort(data: string[]): string[] {
  const groupedData = data.reduce(
    (acc, item) => {
      const arr = item.split(":");
      const prefix = arr.splice(0, 1)[0];
      const last = arr[0] ? arr.join(":") : "";
      if (!acc[prefix]) {
        acc[prefix] = [];
      }
      acc[prefix].push(last);
      return acc;
    },
    {} as Record<string, string[]>,
  );
  const entry = Object.entries(groupedData);
  entry.forEach((it) => {
    it[1].sort((a, b) => a.length - b.length);
  });
  return entry.map(([key, value]) => value.map((it) => (it ? `${key}:${it}` : key))).flat();
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
      const map = splitAndSort(data);
      map.splice(0, 0, "*");
      return map;
    });
  },
};
