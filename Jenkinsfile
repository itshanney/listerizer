pipeline {
    agent any

    tools {
        gradle 'gradle-9.4.1'
        jdk 'jdk-25'
    }

    stages {
        stage('Build & Test') {
            steps {
                sh 'gradle build --no-daemon'
            }
            post {
                always {
                    junit 'build/test-results/test/*.xml'
                }
            }
        }
    }
}
