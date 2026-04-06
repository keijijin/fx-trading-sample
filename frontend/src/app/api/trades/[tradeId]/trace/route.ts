import { NextResponse } from "next/server";

const FX_CORE_BASE_URL = process.env.FX_CORE_BASE_URL ?? "http://localhost:8080";

export async function GET(
  _request: Request,
  context: { params: Promise<{ tradeId: string }> },
) {
  const { tradeId } = await context.params;

  try {
    const response = await fetch(`${FX_CORE_BASE_URL}/api/trades/${tradeId}/trace`, {
      method: "GET",
      cache: "no-store",
    });

    const text = await response.text();

    return new NextResponse(text, {
      status: response.status,
      headers: {
        "Content-Type": response.headers.get("Content-Type") ?? "application/json",
      },
    });
  } catch (error) {
    return NextResponse.json(
      {
        message: "トレード追跡 API へ接続できませんでした。",
        detail: error instanceof Error ? error.message : String(error),
      },
      { status: 502 },
    );
  }
}
