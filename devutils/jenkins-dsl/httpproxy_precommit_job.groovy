import SeedHelper


mavenJob('httpproxy-precommit') {

    
    SeedHelper.configureScm(delegate, '${JK_GITCOMMITID}', true)

    SeedHelper.configureCommonProperties(delegate, [notimer: true])

    parameters {
        stringParam("JK_GITCOMMITID", "refs/heads/master", "ID del commit da buildare oppure riferimento ad un branch o ad un tag, ad esempio:<br>\n<b>refs/heads/master</b>")
    
        stringParam("JK_ADDITIONAL_OPTS", "", "Opzioni da passare allo script di esecuzione dei test.<br>\nSe vi è un parametro non riconoscito dallo script, questo ed i successivi verranno passati come parametri aggiuntivi a maven. Esempio:<br>\n<b>--mat-tests -Dmagnews.tests.usehbasedelivery=true</b>")

        stringParam("JK_NOTIFICATION_RECIPIENTS", "", "Indirizzi email a cui inviare i risultati del job via mail (separati da spazio).<br/>\nNB: Verrà inviata la mail qualunque sia l'esito")

        stringParam("JIRA_ISSUE", "", "Id dell'issue JIRA")
    }

    goals("clean test \${JK_ADDITIONAL_OPTS}")

    SeedHelper.configureJiraPostBuild(delegate)

    SeedHelper.configureCleaner(delegate)
}

