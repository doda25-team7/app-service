# SMS Checker Fronted
This repository contains the fronted of a dummy SMS spam classifier. More information about the SMS checker can be found on [proksch/sms-checker](https://github.com/proksch/sms-checker). 

The repository, organization and the accompanying releases are used to learn about DevOps practices by student group [doda25-team7](https://github.com/doda25-team7). The work was done in the context of the course [DevOps for Distribued Apps (CS4295)](https://studyguide.tudelft.nl/courses/study-guide/educations/14776) at the TU Delft. The groups organization page links to the associated repositories. 

There is [one workflow in this repository](.github/workflows/release.yml), the workflow is automatically ran on push in the main branch. The workflow automatically bumps the patch number, and packages the frontend in a multi-arch container image. The container images are released accompanying the repository on the Github container repository (ghcr.io).

To be able to pull lib-version from the Github Maven repository, we inject the Maven settings file into the Docker container during build. Insperation was taken from https://github.com/doda25-team2/app.