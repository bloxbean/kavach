import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";
export default defineConfig({
  site: "https://bloxbean.github.io",
  base: "/kavach",
  trailingSlash: "always",
  output: "static",
  integrations: [
    starlight({
      title: "kavach.",
      components: { Banner: "./src/components/ExperimentalBanner.astro" },
      description:
        "Programmable accounts for Cardano. Learn the concepts, build locally, and explore the protocol.",
      favicon: "/favicon.svg",
      social: [
        {
          icon: "github",
          label: "GitHub",
          href: "https://github.com/bloxbean/kavach",
        },
      ],
      customCss: ["./src/styles/docs.css"],
      editLink: {
        baseUrl: "https://github.com/bloxbean/kavach/edit/main/www/",
      },
      tableOfContents: { minHeadingLevel: 2, maxHeadingLevel: 3 },
      sidebar: [
        {
          label: "Start here",
          items: [
            { label: "Introduction", slug: "guides/introduction" },
            { label: "Run locally", slug: "guides/quickstart" },
            {
              label: "Create your first account",
              slug: "guides/create-account",
            },
          ],
        },
        {
          label: "Understand Kavach",
          items: [
            { label: "Accounts & keys", slug: "concepts/accounts" },
            { label: "Intent approval & fees", slug: "concepts/signing" },
            { label: "Spending rules & budgets", slug: "concepts/budgets" },
            { label: "Security & recovery", slug: "concepts/security" },
          ],
        },
        {
          label: "Explore",
          items: [
            { label: "White paper · v0.1", slug: "whitepaper" },
            { label: "Roadmap & status", slug: "guides/roadmap" },
            { label: "iPhone companion", slug: "guides/companion" },
          ],
        },
        {
          label: "Protocol reference",
          collapsed: true,
          items: [{ autogenerate: { directory: "reference" } }],
        },
      ],
    }),
  ],
});
