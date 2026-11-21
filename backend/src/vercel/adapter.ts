import type { FunctionUrlEvent, FunctionUrlResponse } from "../core/api";

export interface FridayApi {
  handle(event: FunctionUrlEvent): Promise<FunctionUrlResponse>;
}

export function createVercelHandler(api: FridayApi) {
  return async function handle(request: Request): Promise<Response> {
    const event = await toFunctionEvent(request);
    const response = await api.handle(event);
    const init: ResponseInit = {
      status: response.statusCode,
      ...(response.headers ? { headers: response.headers } : {})
    };
    return new Response(response.body ?? null, init);
  };
}

async function toFunctionEvent(request: Request): Promise<FunctionUrlEvent> {
  const url = new URL(request.url);
  const route = url.searchParams.get("fridayRoute");
  const rawPath = route?.startsWith("auth/") && /^[a-z/]+$/.test(route) ? `/v1/${route}` : route === "register" || route === "warm" || route === "command"
    ? `/v1/${route}`
    : url.pathname;
  const headers = Object.fromEntries(request.headers.entries());
  const contentType = request.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase();
  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  const sourceIp = clientIp(request.headers);
  const requestId = request.headers.get("x-vercel-id") ?? undefined;
  let body: string | undefined;
  let isBase64Encoded = false;

  if (hasBody && contentType === "audio/wav") {
    body = Buffer.from(await request.arrayBuffer()).toString("base64");
    isBase64Encoded = true;
  } else if (hasBody) {
    body = await request.text();
  }

  return {
    rawPath,
    rawQueryString: url.searchParams.toString(),
    headers,
    ...(body === undefined ? {} : { body }),
    isBase64Encoded,
    requestContext: {
      ...(requestId ? { requestId } : {}),
      http: {
        method: request.method,
        ...(sourceIp ? { sourceIp } : {})
      }
    }
  };
}

function clientIp(headers: Headers): string | undefined {
  const forwarded = headers.get("x-vercel-forwarded-for") ?? headers.get("x-forwarded-for");
  return forwarded?.split(",", 1)[0]?.trim() || undefined;
}
