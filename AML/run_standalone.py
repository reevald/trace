#!/usr/bin/env python3
"""
Standalone runner for AML system without Docker
"""

import subprocess
import sys
import time
import signal
import os
from multiprocessing import Process

def run_service(command, name):
    """Run a service with the given command"""
    print(f"Starting {name}...")
    try:
        process = subprocess.Popen(command, shell=True)
        process.wait()
    except KeyboardInterrupt:
        print(f"Stopping {name}...")
        process.terminate()

def main():
    # Check if database is initialized
    if not os.path.exists("database_initialized.flag"):
        print("Initializing database...")
        subprocess.run([sys.executable, "database/init_db.py"])
        with open("database_initialized.flag", "w") as f:
            f.write("initialized")
    
    # Define services
    services = [
        ("uvicorn app.main:app --host 0.0.0.0 --port 8000", "AML API"),
        (f"{sys.executable} services/data_fetcher.py", "Data Fetcher"),
        (f"{sys.executable} services/aml_analyzer.py", "AML Analyzer"),
        (f"{sys.executable} services/email_alerter.py", "Email Alerter")
    ]
    
    processes = []
    
    try:
        # Start all services
        for command, name in services:
            p = Process(target=run_service, args=(command, name))
            p.start()
            processes.append(p)
            time.sleep(2)  # Stagger startup
        
        print("\nAll services started!")
        print("API available at: http://localhost:8000")
        print("API docs at: http://localhost:8000/docs")
        print("\nPress Ctrl+C to stop all services...")
        
        # Wait for all processes
        for p in processes:
            p.join()
            
    except KeyboardInterrupt:
        print("\nShutting down all services...")
        for p in processes:
            p.terminate()
            p.join()
        print("All services stopped.")

if __name__ == "__main__":
    main()