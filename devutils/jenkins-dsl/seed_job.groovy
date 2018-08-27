import SeedHelper

freeStyleJob('httpproxy-seed') {
    SeedHelper.configureScm(delegate)
    SeedHelper.configureCommonProperties(delegate, [junit: false, notimer: true])
    
    steps {
        dsl {
            external('devutils/jenkins-dsl/*_job.groovy')
        }
    }
    SeedHelper.configureCleaner(delegate, 5)
}