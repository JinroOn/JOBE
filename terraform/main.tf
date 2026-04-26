# =============================================
# Provider 설정
# =============================================
provider "aws" {
  region = "ap-northeast-2"
}

# =============================================
# VPC
# =============================================
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "jinroon-vpc"
  }
}

# 인터넷 게이트웨이
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "jinroon-igw"
  }
}

# Public 서브넷
resource "aws_subnet" "public_1" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = "ap-northeast-2a"
  map_public_ip_on_launch = true

  tags = {
    Name = "jinroon-public-1"
  }
}

# 라우팅 테이블
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "jinroon-public-rt"
  }
}

resource "aws_route_table_association" "public_1" {
  subnet_id      = aws_subnet.public_1.id
  route_table_id = aws_route_table.public.id
}

# =============================================
# 보안 그룹
# =============================================
resource "aws_security_group" "ec2" {
  name        = "jinroon-ec2-sg"
  description = "Security group for EC2"
  vpc_id      = aws_vpc.main.id

  # HTTP
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "HTTP"
  }

  # HTTPS
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "HTTPS"
  }

  # Spring Boot
  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "Spring Boot"
  }

  # SSH - 나중에 본인 IP로 변경 권장
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
    description = "SSH"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "jinroon-ec2-sg"
  }
}

# =============================================
# IAM - EC2가 ECR 이미지 pull 할 수 있도록
# =============================================
resource "aws_iam_role" "ec2_role" {
  name = "jinroon-ec2-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
      }
    ]
  })
}

# SSM (AWS 콘솔에서 SSH 없이 접속 가능)
resource "aws_iam_role_policy_attachment" "ssm_policy" {
  role       = aws_iam_role.ec2_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# CloudWatch Agent
resource "aws_iam_role_policy_attachment" "cloudwatch_agent" {
  role       = aws_iam_role.ec2_role.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

# ECR pull 권한
resource "aws_iam_role_policy" "ecr_policy" {
  name = "ecr-pull-policy"
  role = aws_iam_role.ec2_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ecr:GetAuthorizationToken",
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage"
        ]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "ec2_profile" {
  name = "jinroon-ec2-profile"
  role = aws_iam_role.ec2_role.name
}

# =============================================
# 키 페어 (terraform apply 전에 먼저 생성 필요)
# 터미널에서: ssh-keygen -t rsa -b 4096 -f jinroon-key -N ""
# =============================================
resource "aws_key_pair" "jinroon" {
  key_name   = "jinroon-key"
  public_key = file("${path.module}/jinroon-key.pub")
}

# =============================================
# EC2 인스턴스 (프리티어: t2.micro + gp2 30GB)
# =============================================
resource "aws_instance" "app" {
  ami           = "ami-0e9bfdb247cc8de84"  # Ubuntu 22.04 LTS (서울 리전)
  instance_type = "t2.micro"               # 프리티어
  subnet_id     = aws_subnet.public_1.id

  monitoring = false  # 세부 모니터링 OFF (켜면 과금!)

  vpc_security_group_ids = [aws_security_group.ec2.id]
  key_name               = aws_key_pair.jinroon.key_name
  iam_instance_profile   = aws_iam_instance_profile.ec2_profile.name

  root_block_device {
    volume_size = 30     # 프리티어 최대
    volume_type = "gp2"  # 프리티어 (gp3는 과금 주의)
  }

  user_data = <<-EOF
              #!/bin/bash
              set -e

              # 패키지 업데이트
              sudo apt-get update -y

              # SSM Agent
              sudo snap install amazon-ssm-agent --classic
              sudo systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service
              sudo systemctl start snap.amazon-ssm-agent.amazon-ssm-agent.service

              # AWS CLI 설치
              sudo apt-get install -y unzip curl
              curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
              unzip awscliv2.zip
              sudo ./aws/install

              # Docker 설치
              sudo apt-get install -y ca-certificates curl gnupg
              sudo install -m 0755 -d /etc/apt/keyrings
              curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
              sudo chmod a+r /etc/apt/keyrings/docker.gpg
              echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
              sudo apt-get update -y
              sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
              sudo usermod -aG docker ubuntu
              sudo systemctl enable docker
              sudo systemctl start docker

              # Nginx 설치
              sudo apt-get install -y nginx
              sudo systemctl enable nginx
              sudo systemctl start nginx

              # 앱 디렉토리 생성
              mkdir -p /home/ubuntu/app/logs
              chown -R ubuntu:ubuntu /home/ubuntu/app

              echo "EC2 초기 세팅 완료!"
              EOF

  tags = {
    Name = "jinroon-app"
  }
}

# =============================================
# 탄력적 IP (인스턴스에 연결된 상태면 무료!)
# =============================================
resource "aws_eip" "app" {
  instance = aws_instance.app.id
  domain   = "vpc"

  tags = {
    Name = "jinroon-app-eip"
  }

  depends_on = [aws_internet_gateway.main]
}

# =============================================
# ECR (Docker 이미지 저장소 - 월 500MB 무료)
# =============================================
resource "aws_ecr_repository" "backend" {
  name         = "jinroon-backend"
  force_delete = true

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name = "jinroon-backend"
  }
}

# 최근 3개 이미지만 유지 (용량 절약)
resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "최근 3개 이미지만 유지"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 3
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}

# =============================================
# 출력값 (apply 완료 후 여기 값들을 GitHub Secrets에 등록)
# =============================================
output "public_ip" {
  value       = aws_eip.app.public_ip
  description = "EC2 공인 IP → GitHub Secret: EC2_HOST"
}

output "ssh_command" {
  value       = "ssh -i jinroon-key.pem ubuntu@${aws_eip.app.public_ip}"
  description = "SSH 접속 명령어"
}

output "instance_id" {
  value       = aws_instance.app.id
  description = "EC2 인스턴스 ID"
}

output "ecr_repository_url" {
  value       = aws_ecr_repository.backend.repository_url
  description = "ECR URL → GitHub Secret: ECR_REGISTRY 앞부분 등록"
}
