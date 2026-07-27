# Terraform da demo OCI Cache

Este stack cria um OCI Cache na região primária e outro na região de DR. Por
padrão também cria uma VCN, subnet privada e NSG em cada região.

## Recursos criados

- duas VCNs e duas subnets privadas, quando `create_networks=true`;
- um NSG por região, aceitando somente TCP/6379 dos CIDRs informados;
- dois clusters OCI Cache com Valkey 8.1;
- criação opcional de OCI Cache users e associação dos usuários aos clusters;
- outputs com FQDNs, OCIDs, subnets, NSGs e variáveis da aplicação.

O stack não cria OKE, Compute, DRG ou remote peering. A aplicação precisa
executar em uma rede com DNS e rotas para as duas subnets privadas. Em ambientes
corporativos, normalmente essas rotas são providas por DRG, RPC, FastConnect ou
VPN e devem ser tratadas pelo módulo de rede central da organização.

## Permissões IAM

O principal que executa o Terraform precisa administrar o OCI Cache e os
recursos de rede criados pelo stack. Exemplo para um grupo:

```text
Allow group TerraformOperators to manage redis-family in compartment <COMPARTMENT>
Allow group TerraformOperators to manage virtual-network-family in compartment <COMPARTMENT>
```

Restrinja o grupo, compartment e permissões conforme a governança da
organização. Se as redes forem administradas por outra equipe, use redes
existentes e uma política de menor privilégio acordada com essa equipe.

## Uso

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars
# Edite compartment_id, regiões, CIDRs e usuários.
terraform init
terraform fmt -check -recursive
terraform validate
terraform plan -out=tfplan
terraform apply tfplan
```

Na raiz do projeto, a mesma validação pode ser executada com:

```bash
./scripts/terraform-check.sh
```

Depois do apply:

```bash
terraform output spring_boot_environment
terraform output -raw spring_boot_dotenv > ../.env.terraform
```

Revise `.env.terraform`, substitua `OCI_CACHE_PASSWORD=CHANGE_ME` pela senha
obtida do secret manager e carregue o arquivo no shell. A senha em texto puro
nunca é gerada nem exibida pelo Terraform.

## Usuário e senha

O caminho recomendado é criar/gerenciar o OCI Cache user e o segredo com o
processo de segurança da organização, passando os OCIDs em
`*_existing_cache_user_ocids`. É obrigatório associar pelo menos um usuário a
cada cluster. `cache_user_name` deve corresponder ao username usado nesses
usuários.

Para uma demo descartável, gere um hash SHA-256:

```bash
printf '%s' 'SENHA_FORTE' | shasum -a 256
```

Ative `create_cache_user` e informe o hash em
`cache_user_password_hashes`. Mesmo marcado como `sensitive`, o hash fica no
state. Proteja o state com criptografia, controle de acesso e locking.

## Redes existentes

Para não criar VCNs:

```hcl
create_networks = false

primary_existing_vcn_id    = "ocid1.vcn..."
primary_existing_subnet_id = "ocid1.subnet..."
standby_existing_vcn_id    = "ocid1.vcn..."
standby_existing_subnet_id = "ocid1.subnet..."
```

O stack continuará criando um NSG em cada VCN e o associará ao cluster.

## Destruição

Revise o plano antes de destruir. OCI Cache é dado derivado nesta demo, mas
Streams e mensagens na DLQ serão removidos junto com os clusters:

```bash
terraform plan -destroy
terraform destroy
```

## Referências oficiais

- [Provider OCI para Terraform](https://docs.oracle.com/en-us/iaas/tools/terraform-provider-oci/latest/)
- [Recurso `oci_redis_redis_cluster`](https://registry.terraform.io/providers/oracle/oci/latest/docs/resources/redis_redis_cluster)
- [OCI Cache users com Terraform](https://docs.oracle.com/en-us/iaas/Content/ocicache/terraform-config-cache-user.htm)
