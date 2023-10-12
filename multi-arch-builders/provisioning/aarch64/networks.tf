resource "aws_vpc" "vpc" {
  cidr_block           = "172.31.0.0/16"
  tags = {
    Name = "${var.project}-vpc"
  }
}

resource "aws_internet_gateway" "gw" {
  vpc_id   = aws_vpc.vpc.id
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
 count      = length(data.aws_availability_zones.azs.names)
 vpc_id     = aws_vpc.vpc.id
 cidr_block = element(var.private_subnet_cidrs, count.index)
 availability_zone = element(data.aws_availability_zones.azs.names, count.index)
 tags = {
   Name = "${var.project}-private-subnet-${count.index + 1}"
 }
}


resource "aws_route_table" "internet_route" {
  vpc_id   = aws_vpc.vpc.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.gw.id
  }
  tags = {
    Name = "${var.project}-ig"
  }
}

resource "aws_main_route_table_association" "public-set-main-default-rt-assoc" {
  vpc_id         = aws_vpc.vpc.id
  route_table_id = aws_route_table.internet_route.id
}
