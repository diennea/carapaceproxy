import SeedHelper

mavenJob('httpproxy-spotbugs') {
    
    SeedHelper.configureScm(delegate)
    SeedHelper.configureCommonProperties(delegate, [junit: false])

    goals("clean install spotbugs:check -DskipTests")

    SeedHelper.configureCleaner(delegate, 4, 3)


}

