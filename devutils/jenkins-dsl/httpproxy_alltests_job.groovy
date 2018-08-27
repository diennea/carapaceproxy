import SeedHelper

mavenJob('httpproxy-alltest') {
    
    SeedHelper.configureScm(delegate)
    SeedHelper.configureCommonProperties(delegate, [noConcurrentBuild: true])

    goals("clean test")

    SeedHelper.configureCleaner(delegate)


}

