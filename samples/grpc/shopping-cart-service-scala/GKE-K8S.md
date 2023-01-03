# Steps for running this sample on GKE

### Create Cloud SQL instance (in the right region)

https://console.cloud.google.com/sql/instances?project=akka-team
 
### Publish docker image to gcr.io

```
     gcloud auth print-access-token | docker login -u oauth2accesstoken --password-stdin https://gcr.io
     sbt Docker/publish -Ddocker.registry=gcr.io -Ddocker.username=akka-team
```

### Create k8s cluster

In the same region as the db.

Or use an existing one?

### Set up kubectl access

    gcloud container clusters get-credentials res-grpc-test-dca-cluster --region us-east1 --project akka-team

### Create the akka-grpc-projection namespace

    kubectl --context gke_akka-team_us-east1_res-grpc-test-dca-cluster create namespace akka-grpc-projection

### Set DB Access secrets

Using the db password chosen when creating the db:

    kubectl --context gke_akka-team_us-east1_res-grpc-test-dca-cluster --namespace akka-grpc-projection create secret generic benchmark-db-secret --from-literal=username=postgres --from-literal=password='[db-password]'

### RBAC

To allow akka management to read the pods from k8s API,

Possibly but not sure - I don't have permissions enough to give the service account any perms: First we need a service account, create a service account named `akka-grpc-projection` at: https://console.cloud.google.com/iam-admin/serviceaccounts?project=akka-team

Then deploy the rbac


    kubectl --context gke_akka-team_us-east1_res-grpc-test-dca-cluster --namespace akka-grpc-projection create -f k8s/rbac.yaml

FIXME Looks like I don't have enough permissions for this on the akka-team project:

> Error from server (Forbidden): error when creating "k8s/rbac.yaml": roles.rbac.authorization.k8s.io is forbidden: User "johan.andren@lightbend.com" cannot create resource "roles" in API group "rbac.authorization.k8s.io" in the namespace "akka-grpc-projection": requires one of ["container.roles.create"] permission(s).
> Error from server (Forbidden): error when creating "k8s/rbac.yaml": rolebindings.rbac.authorization.k8s.io is forbidden: User "johan.andren@lightbend.com" cannot create resource "rolebindings" in API group "rbac.authorization.k8s.io" in the namespace "akka-grpc-projection": requires one of ["container.roleBindings.create"] permission(s).




### Create public ingress/load balancers for the service

    kubectl --context gke_akka-team_us-east1_res-grpc-test-dca-cluster --namespace akka-grpc-projection create -f k8s/external-service.yaml

Public endpoint IP can now be seen through https://console.cloud.google.com/kubernetes/discovery?project=akka-team

FIXME should it be HTTPS instead since replicating across public internet?

### Deploy the service

For Replicated ES We need to know all the DC public endpoints up front somehow, and put them in the respective deployment.yaml