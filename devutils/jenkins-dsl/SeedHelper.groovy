class SeedHelper {

    // only this line differs from MN
    private static final String SCM_URL = 'ssh://git@sviluppo25-cs7.sviluppo.dna:7999/mn/httpproxy.git';
    
    private static final String SCM_CREDENTIALS = 'BitbucketMN';
    private static final String DEFAULT_BRANCH = "*/master"
    private static final String JOBS_LABEL = "docker"
    private static final String DEFAULT_TRIGGER_TIMER = "H H * * *"
    private static final String SUREFIRE_REPORT_DIR = "**/target/surefire-reports/*.xml"
    
    private static addJiraCommentExecution(Node parent, boolean success) {
        Node obj = parent.appendNode('org.jenkins__ci.plugins.flexible__publish.ConditionalPublisher')
        Node cond = obj.appendNode('condition', [class: 'org.jenkins_ci.plugins.run_condition.core.StatusCondition'])
        Node worst = cond.appendNode('worstResult')

        worst.appendNode('name').setValue(success ? 'SUCCESS': 'ABORTED')
        worst.appendNode('ordinal').setValue(success ? '0': '4')
        worst.appendNode('color').setValue(success ? 'BLUE': 'ABORTED')
        worst.appendNode('completeBuild').setValue(success ? 'true': 'false')

        Node best = cond.appendNode('bestResult')
        best.appendNode('name').setValue(success ? 'SUCCESS': 'UNSTABLE')
        best.appendNode('ordinal').setValue(success ? '0': '1')
        best.appendNode('color').setValue(success ? 'BLUE': 'YELLOW')
        best.appendNode('completeBuild').setValue('true')
        obj.appendNode('runner', [class: 'org.jenkins_ci.plugins.run_condition.BuildStepRunner$Fail'])
        obj.appendNode('executionStrategy', [class: 'org.jenkins_ci.plugins.flexible_publish.strategy.FailAtEndExecutionStrategy'])

        Node step = obj.appendNode('publisherList').appendNode('org.jenkinsci.plugins.jiraext.view.JiraExtBuildStep')

        step.appendNode('issueStrategy', [class: 'org.jenkinsci.plugins.jiraext.view.SingleTicketStrategy']).appendNode('issueKey').setValue('$JIRA_ISSUE')
        Node addComment = step.appendNode('extensions').appendNode('org.jenkinsci.plugins.jiraext.view.AddComment')
        addComment.appendNode('postCommentForEveryCommit').setValue('false')
        String buildStatus = success ? '*{color:#14892c}BUILD SUCCESS{color}*': '*{color:#d04437}BUILD FAILED{color}*'
        addComment.appendNode('commentText').setValue(""+
                    "Precommit job finished\n"+
                    "Build URL:*[\$BUILD_URL]*\n"+
                    "Git branch: *\$GIT_BRANCH*\n"+
                    "Git commit id: *\$GIT_COMMIT*\n"+
                    buildStatus)
    }
    static configureJiraPostBuild(ctx) {
        ctx.configure { p -> 
            Node flexPublishers = p / 'publishers' / 'org.jenkins__ci.plugins.flexible__publish.FlexiblePublisher' / 'publishers'
            SeedHelper.addJiraCommentExecution(flexPublishers, true)
            SeedHelper.addJiraCommentExecution(flexPublishers, false)
        }

    }

    static configureScm(ctx, String branchValue = DEFAULT_BRANCH, boolean addMerge = false) {
        if (!addMerge) {
            ctx.scm {
                git {
                    remote {
                        url(SCM_URL)
                        credentials(SCM_CREDENTIALS)
                        branch(branchValue)
                    }

                    extensions {
                        cloneOptions {
                            shallow(true)
                            depth(1)
                            noTags(true)
                        }
                    }
                }
            }
        } else {
            ctx.scm {
                git {
                    remote {
                        url(SCM_URL)
                        credentials(SCM_CREDENTIALS)
                        branch(branchValue)
                    }

                    extensions {
                        cloneOptions {
                            shallow(true)
                            depth(30)
                            noTags(false)
                        }
                        mergeOptions {
                            branch('master')
                            remote('origin')
                            strategy('default')
                        }
                    }
                }   
            }   
            ctx.configure { project ->
                            java.util.List children = project.children()
            for (Node child: children) {
                if (child.name().equals('scm')) {
                    for (Node scmChild: child.children()) {
                        if (scmChild.name().equals('extensions')) {
                            Node userIdentity = scmChild.appendNode('hudson.plugins.git.extensions.impl.UserIdentity')
                            userIdentity.appendNode('name').setValue('magnews.jenkins.bot')
                            userIdentity.appendNode('email').setValue('dev@diennea.com')
                        }
                    }
                }
            }

            }


        }        
    }
    static configureCleaner(ctx, int daysToKeepValue = 10, int numToKeepValue = 10) {
        ctx.logRotator {
            daysToKeep(daysToKeepValue)
            numToKeep(numToKeepValue)
                        }

        ctx.publishers {
            wsCleanup {
                deleteDirectories(false)
                
                cleanWhenSuccess(true)
                cleanWhenUnstable(true)
                cleanWhenFailure(true)
                cleanWhenNotBuilt(true)
                    }
                }   
            }   


    static configureCommonProperties(ctx, Map options = [:]) {

        if (!options.containsKey('noConcurrentBuild')) {
            ctx.concurrentBuild(true)
        }
        ctx.label(JOBS_LABEL)

        def triggerTimer = DEFAULT_TRIGGER_TIMER
        if (options.'triggertimer') {
            triggerTimer = options.'triggertimer'
        }        
        if (!options.containsKey('notimer')) {
            ctx.triggers {
                cron(triggerTimer)
            }
        }
        

        def junit = !options.containsKey('junit') || options.'junit'
        if (junit) {
            ctx.publishers {
                archiveJunit(SUREFIRE_REPORT_DIR) {
                    healthScaleFactor(1.0)
                }   
            }
        }
        
    }
}