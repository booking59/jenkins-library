import hudson.AbortException
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.parser.ParserException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder

import com.lesfurets.jenkins.unit.BasePipelineTest

import util.JenkinsLoggingRule
import util.JenkinsShellCallRule
import util.Rules

public class MTABuildTest extends BasePipelineTest {

    private ExpectedException thrown = new ExpectedException()
    private TemporaryFolder tmp = new TemporaryFolder()
    private JenkinsLoggingRule jlr = new JenkinsLoggingRule(this)
    private JenkinsShellCallRule jscr = new JenkinsShellCallRule(this)

    @Rule
    public RuleChain ruleChain = Rules.getCommonRules(this)
            .around(thrown)
            .around(tmp)
            .around(jlr)
            .around(jscr)


    def currentDir
    def mtaYaml

    def mtaBuildScript
    def cpe

    @Before
    void init() {

        currentDir = tmp.newFolder().toURI().getPath()[0..-2] //omit final '/'
        mtaYaml = new File("$currentDir/mta.yaml")
        mtaYaml << defaultMtaYaml()

        helper.registerAllowedMethod('pwd', [], { currentDir } )

        binding.setVariable('PATH', '/usr/bin')

        mtaBuildScript = loadScript('mtaBuild.groovy').mtaBuild
        cpe = loadScript('commonPipelineEnvironment.groovy').commonPipelineEnvironment
    }


    @Test
    void environmentPathTest() {

        mtaBuildScript.call(buildTarget: 'NEO')

        assert jscr.shell[1].contains('PATH=./node_modules/.bin:/usr/bin')
    }


    @Test
    void sedTest() {

        mtaBuildScript.call(buildTarget: 'NEO')

        assert jscr.shell[0] =~ /sed -ie "s\/\\\$\{timestamp\}\/`date \+%Y%m%d%H%M%S`\/g" ".*\/mta.yaml"$/
    }


    @Test
    void mtarFilePathFromCommonPipelineEnviromentTest() {

        mtaBuildScript.call(script: [commonPipelineEnvironment: cpe],
                      buildTarget: 'NEO')

        def mtarFilePath = cpe.getMtarFilePath()

        assert mtarFilePath == "$currentDir/com.mycompany.northwind.mtar"
    }


    @Test
    void mtaBuildWithSurroundingDirTest() {

        def newDirName = 'newDir'
        def newDirPath = "$currentDir/$newDirName"
        def newDir = new File(newDirPath)

        newDir.mkdirs()
        new File(newDir, 'mta.yaml') << defaultMtaYaml()

        helper.registerAllowedMethod('pwd', [], { newDirPath } )

        def mtarFilePath = mtaBuildScript.call(buildTarget: 'NEO')

        assert jscr.shell[0] =~ /sed -ie "s\/\\\$\{timestamp\}\/`date \+%Y%m%d%H%M%S`\/g" ".*\/newDir\/mta.yaml"$/

        assert mtarFilePath == "$currentDir/$newDirName/com.mycompany.northwind.mtar"
    }


    @Test
    void mtaJarLocationNotSetTest() {

        mtaBuildScript.call(buildTarget: 'NEO')

        assert jscr.shell[1].contains(' -jar mta.jar --mtar ')

        assert jlr.log.contains('[mtaBuild] Using MTA JAR from current working directory.')
    }


    @Test
    void mtaJarLocationAsParameterTest() {

        mtaBuildScript.call(mtaJarLocation: '/mylocation/mta', buildTarget: 'NEO')

        assert jscr.shell[1].contains(' -jar /mylocation/mta/mta.jar --mtar ')

        assert jlr.log.contains('[mtaBuild] MTA JAR "/mylocation/mta/mta.jar" retrieved from configuration.')
    }


    @Test
    void noMtaPresentTest() {

        mtaYaml.delete()
        thrown.expect(FileNotFoundException)

        mtaBuildScript.call(buildTarget: 'NEO')
    }


    @Test
    void badMtaTest() {

        thrown.expect(ParserException)
        thrown.expectMessage('while parsing a block mapping')

        mtaYaml.text = badMtaYaml()

        mtaBuildScript.call(buildTarget: 'NEO')
    }


    @Test
    void noIdInMtaTest() {

        thrown.expect(AbortException)
        thrown.expectMessage("Property 'ID' not found in mta.yaml file at: '")

        mtaYaml.text = noIdMtaYaml()

        mtaBuildScript.call(buildTarget: 'NEO')
    }


    @Test
    void mtaJarLocationFromEnvironmentTest() {

        binding.setVariable('env', [:])
        binding.getVariable('env')['MTA_JAR_LOCATION'] = '/env/mta'

        mtaBuildScript.call(buildTarget: 'NEO')

        assert jscr.shell[1].contains('-jar /env/mta/mta.jar --mtar')
        assert jlr.log.contains('[mtaBuild] MTA JAR "/env/mta/mta.jar" retrieved from environment.')
    }


    @Test
    void mtaJarLocationFromCustomStepConfigurationTest() {

        cpe.configuration = [general:[mtaJarLocation: '/general/mta']]

        mtaBuildScript.call(script: [commonPipelineEnvironment: cpe],
                      buildTarget: 'NEO')

        assert jscr.shell[1].contains('-jar /general/mta/mta.jar --mtar')
        assert jlr.log.contains('[mtaBuild] MTA JAR "/general/mta/mta.jar" retrieved from configuration.')
    }


    @Test
    void buildTargetFromParametersTest() {

        mtaBuildScript.call(buildTarget: 'NEO')

        assert jscr.shell[1].contains('java -jar mta.jar --mtar com.mycompany.northwind.mtar --build-target=NEO build')
    }


    @Test
    void buildTargetFromCustomStepConfigurationTest() {

        cpe.configuration = [steps:[mtaBuild:[buildTarget: 'NEO']]]

        mtaBuildScript.call(script: [commonPipelineEnvironment: cpe])

        assert jscr.shell[1].contains('java -jar mta.jar --mtar com.mycompany.northwind.mtar --build-target=NEO build')
    }


    @Test
    void buildTargetFromDefaultStepConfigurationTest() {

        cpe.defaultConfiguration = [steps:[mtaBuild:[buildTarget: 'NEO']]]

        mtaBuildScript.call(script: [commonPipelineEnvironment: cpe])

        assert jscr.shell[1].contains('java -jar mta.jar --mtar com.mycompany.northwind.mtar --build-target=NEO build')
    }


    private defaultMtaYaml() {
        return  '''
                _schema-version: "2.0.0"
                ID: "com.mycompany.northwind"
                version: 1.0.0

                parameters:
                  hcp-deployer-version: "1.0.0"

                modules:
                  - name: "fiorinorthwind"
                    type: html5
                    path: .
                    parameters:
                       version: 1.0.0-${timestamp}
                    build-parameters:
                      builder: grunt
                build-result: dist
                '''
    }

    private badMtaYaml() {
        return  '''
                _schema-version: "2.0.0
                ID: "com.mycompany.northwind"
                version: 1.0.0

                parameters:
                  hcp-deployer-version: "1.0.0"

                modules:
                  - name: "fiorinorthwind"
                    type: html5
                    path: .
                    parameters:
                       version: 1.0.0-${timestamp}
                    build-parameters:
                      builder: grunt
                build-result: dist
                '''
    }

    private noIdMtaYaml() {
        return  '''
                _schema-version: "2.0.0"
                version: 1.0.0

                parameters:
                  hcp-deployer-version: "1.0.0"

                modules:
                  - name: "fiorinorthwind"
                    type: html5
                    path: .
                    parameters:
                       version: 1.0.0-${timestamp}
                    build-parameters:
                      builder: grunt
                build-result: dist
                '''
    }
}
