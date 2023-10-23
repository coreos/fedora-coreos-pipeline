resource "aws_vpc" "vpc" {
  count = var.distro == "fcos" ? 1 : 0
  cidr_block           = "172.31.0.0/16"
  tags = {
    Name = "${var.project}-vpc"
  }
}

resource "aws_internet_gateway" "gw" {
  count = var.distro == "fcos" ? 1 : 0
  vpc_id   = aws_vpc.vpc[0].id
}

data "aws_availability_zones" "azs" {
  state    = "available"
}

variable "private_subnet_cidrs" {
 type        = list(string)
 description = "Private Subnet CIDR values"
 default     = ["172.31.1.0/24", "172.31.2.0/24", "172.31.3.0/24", "172.31.4.0/24", "172.31.5.0/24", "172.31.6.0/24", "172.31.7.0/24", "172.31.8.0/24"]
}

resource "aws_subnet" "private_subnets" {
 count      = var.distro == "fcos" ? length(data.aws_availability_zones.azs.names) : 0
 vpc_id     = aws_vpc.vpc[0].id
 cidr_block = element(var.private_subnet_cidrs, count.index)
 availability_zone = element(data.aws_availability_zones.azs.names, count.index)
 tags = {
   Name = "${var.project}-private-subnet-${count.index + 1}"
 }
}


resource "aws_route_table" "internet_route" {
  count = var.distro == "fcos" ? 1 : 0
  vpc_id   = aws_vpc.vpc[0].id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.gw[0].id
  }
  tags = {
    Name = "${var.project}-ig"
  }
}

resource "aws_main_route_table_association" "public-set-main-default-rt-assoc" {
  count = var.distro == "fcos" ? 1 : 0
  vpc_id         = aws_vpc.vpc[0].id
  route_table_id = aws_route_table.internet_route[0].id
}
