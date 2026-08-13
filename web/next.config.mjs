/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  output: "standalone",
  async rewrites() {
    // The browser talks to one origin; the gateway routes by path in deployed environments.
    return [
      { source: "/api/incidents/:path*", destination: `${process.env.INCIDENT_API_URL ?? "http://localhost:8083"}/v1/incidents/:path*` },
      { source: "/api/analytics/:path*", destination: `${process.env.INCIDENT_API_URL ?? "http://localhost:8083"}/v1/analytics/:path*` },
      { source: "/api/catalog/:path*", destination: `${process.env.INCIDENT_API_URL ?? "http://localhost:8083"}/v1/catalog/:path*` },
      { source: "/api/streams/:path*", destination: `${process.env.INCIDENT_API_URL ?? "http://localhost:8083"}/v1/streams/:path*` },
      { source: "/api/auth/:path*", destination: `${process.env.INCIDENT_API_URL ?? "http://localhost:8083"}/v1/auth/:path*` },
      { source: "/api/insight/:path*", destination: `${process.env.INSIGHT_API_URL ?? "http://localhost:8084"}/v1/incidents/:path*` },
    ];
  },
};

export default nextConfig;
