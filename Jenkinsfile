pipeline {
    // Executes the pipeline on any available Jenkins agent
    agent any

    tools {
        gradle 'gradle-9.4.1'
        jdk 'jdk-25'
    }

    // Defines environment variables available to all stages
    environment {
        APP_NAME = "listerizer"
    }

    stages {
        stage('Build') {
            steps {
                echo "Building ${APP_NAME}..."
                sh 'gradle assemble -x test'
            }
        }

        stage('Unit Test') {
            steps {
                echo 'Running unit tests...'
                sh 'gradle test'
            }
        }

        stage('Integration Test') {
            steps {
                echo 'Running integration tests...'
                sh 'gradle integrationTest'
            }
        }
    }

    // The post section runs after all stages finish, regardless of success/failure
    post {
        always {
            echo 'Pipeline finished. Archiving test results...'
            // Tells Jenkins to ingest the JUnit XML files to generate the UI test reports
            junit 'build/test-results/**/*.xml'

            // Saves the actual compiled JAR so you can download it from the Jenkins UI
            archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true
        }
        success {
            echo '✅ Pipeline completed successfully!'
            // You could add Slack or Email notification commands here
        }
        failure {
            echo '❌ Pipeline failed. Please check the logs.'
        }
    }
}
