export { UI_VERSION, UI_COMMIT, formatVersion };

// Both values are baked in by Maven at build time (VUE_APP_VERSION, VUE_APP_COMMIT); undefined under `yarn serve`
const UI_VERSION = process.env.VUE_APP_VERSION || "dev";
// Left as the raw Maven placeholder when the build had no .git directory
const UI_COMMIT = process.env.VUE_APP_COMMIT?.startsWith("${") ? undefined : process.env.VUE_APP_COMMIT;

// The commit only tells builds apart within the same -SNAPSHOT; a release is identified by its version alone
function formatVersion(version, commit) {
    if (!version) {
        return "";
    }
    return version.endsWith("-SNAPSHOT") && commit ? `${version} · ${commit}` : version;
}
