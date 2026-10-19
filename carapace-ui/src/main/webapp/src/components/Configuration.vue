<template>
    <div>
        <div class="page-header">
            <h2>Configuration</h2>
            <b-form-checkbox name="check-button"
                             v-model="checked"
                             size="lg"
                             switch
                             @change="onMaintenanceModeChange()">
                Maintenance Mode
            </b-form-checkbox>
        </div>
        <b-alert
            id="status-alert"
            fade
            dismissible
            :show="opMessage ? countdown : 0"
            @dismiss-count-down="countdown = $event"
            @dismissed="opMessage = ''"
            :variant="opSuccess ? 'success' : 'danger'"
            >{{opMessage}}</b-alert>
        <b-form-textarea
            id="config-editor"
            rows="30"
            max-rows="30"
            no-resize
            v-model="configuration"
            :state="editorState"
            placeholder="Edit Dynamic Configuration">
        </b-form-textarea>
        <div>
            <b-button variant="outline-secondary" @click="fetch()">Fetch</b-button>
            <b-button variant="outline-secondary" @click="validate">Validate</b-button>
            <b-button @click="save">Save</b-button>
            <b-button variant="outline-secondary" @click="downloadBackup">Download backup</b-button>
            <b-button variant="outline-secondary" @click="$refs.backupFile.click()">Upload backup</b-button>
            <input ref="backupFile" type="file" accept="application/json" hidden @change="uploadBackup">
        </div>
        <div>
            <b-modal
                id="maintenance"
                title="Maintenance"
                cancel-title="Close"
                no-close-on-backdrop
                @ok="setupMaintenanceMode"
                @hidden="reset"
                >
                <p class="my-4" v-if="checked">You are about to enable maintenance mode</p>
                <p class="my-4" v-else>You are about to disable maintenance mode</p>
            </b-modal>
        </div>
    </div>
</template>

<script>
    import { doGet } from "../serverapi";
    import { doPost } from "../serverapi";

    export default {
        name: "Configuration",
        data() {
            return {
                configuration: "",
                opSuccess: null,
                editorState: null,
                opMessage: "",
                countdown: 0,
                checked: false
            };
        },
        created() {
            this.fetch();
            this.getMaintenanceStatus();
        },
        methods: {
            save() {
                doPost(
                        "/api/config/apply",
                        this.configuration,
                        () => {
                            this.opSuccess = true
                            this.editorState = true
                            this.opMessage = "Configuration saved successfully."
                            this.countdown = 10
                        },
                        error => {
                            this.opSuccess = false
                            this.editorState = false
                            this.opMessage = "Error: unable to save the configuration (" + error.message +")."
                            this.countdown = 10
                        }
                );
            },
            fetch(okMessage) {
                doGet(
                        "/api/config",
                        conf => {
                            this.configuration = conf
                            this.opSuccess = true
                            this.editorState = true
                            this.opMessage = okMessage || ""
                            this.countdown = 10
                        },
                        error => {
                            this.opSuccess = false
                            this.editorState = false
                            this.opMessage = "Error: unable to fetch current configuration (" + error.message + ")."
                            this.countdown = 10
                        }
                );
            },
            validate() {
                doPost(
                        "/api/config/validate",
                        this.configuration,
                        () => {
                            this.opSuccess = true
                            this.editorState = true
                            this.opMessage = "Configuration is ok."
                            this.countdown = 10
                        },
                        error => {
                            this.opSuccess = false
                            this.editorState = false
                            this.opMessage = "Error: unable to validate the configuration (" + error.message + ")."
                            this.countdown = 10
                        }
                );
            },
            onMaintenanceModeChange() {
                const newChecked = this.checked;
                this.checked = !newChecked;
                const message = this.$createElement('div', {
                    domProps: {
                        innerHTML: 'System gonna <strong>' + (newChecked ? 'enter' : 'exit') + '</strong> maintenance mode, proceed?'
                    }
                });
                this.$bvModal.msgBoxConfirm(message, {
                    title: 'Attention',
                    okVariant: newChecked ? 'warning' : "success",
                    okTitle: 'Yes',
                    footerClass: 'p-2',
                    hideHeaderClose: false,
                    centered: true
                }).then(value => {
                    if (value != true) {
                        return;
                    }
                    doPost("/api/config/maintenance?enable=" + newChecked,
                            newChecked,
                            () => window.location.reload(),
                            error => {
                                this.opSuccess = false
                                this.opMessage = "Something went wrong (" + error.message + ")"
                                this.countdown = 10
                            }
                    );
                })
            },
            getMaintenanceStatus() {
                doGet("/api/config/maintenance", data => {
                    this.checked = data.ok;
                });
            },
            reset() {
                this.getMaintenanceStatus();
            },
            showError(message) {
                this.opSuccess = false
                this.opMessage = "Error: " + message
                this.countdown = 10
            },
            responseMessage(response) {
                return response.json().then(body => body.message, () => response.statusText);
            },
            downloadBackup() {
                fetch("/api/config/backup").then(response => {
                    if (!response.ok) {
                        return this.responseMessage(response).then(message => this.showError(response.status === 503
                                ? "the database does not answer, try again later (" + message + ")."
                                : "unable to download the backup (" + message + ")."));
                    }
                    return response.blob().then(blob => {
                        const filename = /filename="([^"]+)"/.exec(response.headers.get("Content-Disposition") || "");
                        const link = document.createElement("a");
                        link.href = URL.createObjectURL(blob);
                        link.download = filename ? filename[1] : "carapace-state.json";
                        link.click();
                        setTimeout(() => URL.revokeObjectURL(link.href));
                    });
                }).catch(error => this.showError("unable to download the backup (" + error.message + ")."));
            },
            async uploadBackup(event) {
                const file = event.target.files[0];
                // the same file can be chosen again
                event.target.value = "";
                if (!file) {
                    return;
                }
                try {
                    const archive = await file.text();
                    let archived = null;
                    try {
                        archived = JSON.parse(archive).carapaceVersion;
                    } catch (e) {
                        // the server judges the archive
                    }
                    const running = await new Promise(resolve => doGet("/api/version", data => resolve(data.version), () => resolve(null)));
                    const release = version => typeof version === "string" ? ((version.match(/^(\d+)\.(\d+)\.(\d+)/) || []).slice(1).map(Number)) : [];
                    const [a, r] = [release(archived), release(running)];
                    // major, then minor, then patch; 0 when either side is not a release
                    const order = a.length && r.length ? (a[0] - r[0] || a[1] - r[1] || a[2] - r[2]) : 0;
                    const newerVersion = order > 0;
                    const h = this.$createElement;
                    const message = h("div", [
                        h("p", "The restore replaces the whole state on every node; admin writes are paused while it runs. Proceed?"),
                        newerVersion ? h("p", { class: "alert alert-warning" },
                                "Archive taken on Carapace " + archived + ", running " + running + ": settings this version does not know are ignored.") : null
                    ]);
                    const confirmed = await this.$bvModal.msgBoxConfirm(message, {
                        title: "Restore " + file.name,
                        okVariant: "warning",
                        okTitle: "Restore",
                        footerClass: "p-2",
                        centered: true
                    });
                    if (confirmed !== true) {
                        return;
                    }
                    const response = await fetch("/api/config/restore" + (newerVersion ? "?acceptNewerVersion=true" : ""), {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: archive
                    });
                    const outcome = await this.responseMessage(response);
                    if (response.ok) {
                        this.fetch("Backup restored (" + outcome + ").");
                        this.getMaintenanceStatus();
                    } else if (response.status === 422) {
                        this.showError("restore refused, nothing changed: " + outcome);
                    } else if (response.status === 503) {
                        this.showError("another restore or the database is in the way, try again later: " + outcome);
                    } else if (response.status === 500) {
                        this.showError("repeat the restore: " + outcome);
                    } else {
                        this.showError("unable to restore the backup (" + outcome + ").");
                    }
                } catch (error) {
                    this.showError("unable to restore the backup (" + error.message + ").");
                }
            }
        }
    };
</script>

<style lang="scss" scoped>
    @import "../variables.scss";

    .page-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
    }

    #config-editor {
        height: 72vh;
        margin: 0.5rem auto;
    }

    #status-alert {
        position: absolute;
        top: 2rem;
        left: 25%;
        right: 25%;
        z-index: 999;
    }

    button {
        margin-right: 0.5rem;
    }
</style>